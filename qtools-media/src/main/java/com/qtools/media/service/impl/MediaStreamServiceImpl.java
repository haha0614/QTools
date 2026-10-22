package com.qtools.media.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaStreamService;

/**
 * 实时转码 服务实现
 *
 * <p>与 Node 版 /api/stream 行为对齐：</p>
 * <ul>
 *   <li>并发上限（默认 2），超出立刻 429，名额在启动 ffmpeg 前占用；</li>
 *   <li>先拿到首字节再回 200，超时（15s）回 504，避免客户端拿到空响应；</li>
 *   <li>客户端断开（写失败）或正常结束都必须 kill 进程，否则僵尸进程会占满名额；</li>
 *   <li>ffmpeg 缺失回 503。</li>
 * </ul>
 *
 * @author qtools
 */
@Service
public class MediaStreamServiceImpl implements IMediaStreamService
{
    private static final Logger log = LoggerFactory.getLogger(MediaStreamServiceImpl.class);

    /** 等首字节的超时上限 */
    private static final long FIRST_BYTE_TIMEOUT_MS = 15_000L;

    /** 管道拷贝缓冲区 */
    private static final int COPY_BUFFER = 64 * 1024;

    /** 单条 stderr 尾部保留长度（只用于日志诊断） */
    private static final int STDERR_TAIL = 2000;

    private static final String CONTENT_TYPE_MP4 = "video/mp4";

    /** 档位 -> 转码参数；w/h 用于 scale 上限，保证小片源不放大 */
    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static
    {
        PRESETS.put("720", new Preset(1280, 720, "1500k", "1700k", "3000k", "96k"));
        PRESETS.put("480", new Preset(854, 480, "700k", "800k", "1500k", "64k"));
        PRESETS.put("360", new Preset(640, 360, "400k", "450k", "900k", "48k"));
    }

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaFileService mediaFileService;

    /** 已占用的转码名额 */
    private final AtomicInteger active = new AtomicInteger();

    /** 只用于阻塞式读取首字节与排空 stderr，线程均为守护线程 */
    private final ExecutorService pipeExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "media-ffmpeg-pipe");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public int getActive()
    {
        return active.get();
    }

    @Override
    public int getMax()
    {
        return Math.max(1, mediaProperties.getMaxTranscode());
    }

    @Override
    public List<String> getPresets()
    {
        return new ArrayList<>(PRESETS.keySet());
    }

    @Override
    public boolean isFfmpegAvailable()
    {
        String ffmpeg = mediaProperties.getFfmpeg();
        if (StringUtils.isEmpty(ffmpeg))
        {
            return false;
        }
        try
        {
            return Files.isRegularFile(Paths.get(ffmpeg));
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    @Override
    public void transcode(String p, String q, String t, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        if (!isFfmpegAvailable())
        {
            throw new MediaException(503, "ffmpeg 不可用，无法转码（请配置 qtools.media.ffmpeg / QTOOLS_MEDIA_FFMPEG）");
        }
        if (StringUtils.isEmpty(p))
        {
            throw MediaException.badRequest("missing p");
        }
        Preset preset = q == null ? null : PRESETS.get(q);
        if (preset == null)
        {
            throw MediaException.badRequest("bad q");
        }
        int start = parseStart(t);
        // 越权/不存在要在占用名额之前判掉
        Path file = mediaFileService.resolveMedia(p);

        if ("HEAD".equalsIgnoreCase(request.getMethod()))
        {
            response.setStatus(200);
            response.setContentType(CONTENT_TYPE_MP4);
            response.setHeader("Cache-Control", "no-store");
            return;
        }

        acquire();
        long begin = System.currentTimeMillis();
        Process process = null;
        String endReason = "unknown";
        long sent = 0;
        try
        {
            process = startProcess(file, preset, start);
            byte first = readFirstByte(process);

            response.setStatus(200);
            response.setContentType(CONTENT_TYPE_MP4);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            ServletOutputStream out = response.getOutputStream();
            out.write(first);
            sent = 1;

            byte[] buffer = new byte[COPY_BUFFER];
            InputStream stdout = process.getInputStream();
            try
            {
                int read;
                while ((read = stdout.read(buffer)) != -1)
                {
                    out.write(buffer, 0, read);
                    sent += read;
                }
                out.flush();
                endReason = "done";
            }
            catch (IOException e)
            {
                // 客户端主动断开（Broken pipe / Connection reset）：直接结束，由 finally kill 进程
                endReason = "client-close";
            }
        }
        finally
        {
            destroy(process);
            release();
            log.info("媒体转码结束 q={} t={} file={} sent={}KB end={} cost={}ms", q, start, p, sent / 1024,
                    endReason, System.currentTimeMillis() - begin);
        }
    }

    // ------------------------------------------------------------------ 进程

    private Process startProcess(Path file, Preset preset, int start) throws IOException
    {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(file, preset, start));
        builder.redirectErrorStream(false);
        Process process = builder.start();
        drainError(process, file.getFileName().toString());
        return process;
    }

    /** 首字节：拿到即证明 ffmpeg 真的开始出流；超时/进程提前退出都算失败 */
    private byte readFirstByte(Process process) throws IOException
    {
        Future<Integer> future = pipeExecutor.submit(() -> {
            try
            {
                return process.getInputStream().read();
            }
            catch (IOException e)
            {
                return -1;
            }
        });
        int first;
        try
        {
            first = future.get(FIRST_BYTE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            future.cancel(true);
            destroy(process);
            throw new MediaException(504, "转码超时");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new MediaException(500, "转码失败");
        }
        catch (ExecutionException e)
        {
            destroy(process);
            throw new MediaException(500, "转码失败");
        }
        if (first < 0)
        {
            destroy(process);
            throw new MediaException(500, "转码失败");
        }
        return (byte) first;
    }

    /** 排空 stderr，避免管道写满把 ffmpeg 卡死；只保留尾部供日志诊断 */
    private void drainError(Process process, String label)
    {
        pipeExecutor.execute(() -> {
            StringBuilder tail = new StringBuilder();
            try (InputStream stderr = process.getErrorStream())
            {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stderr.read(buffer)) != -1)
                {
                    if (tail.length() < STDERR_TAIL)
                    {
                        tail.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            }
            catch (IOException e)
            {
                return;
            }
            if (tail.length() > 0 && log.isDebugEnabled())
            {
                log.debug("ffmpeg stderr [{}]: {}", label, tail.toString().trim());
            }
        });
    }

    /** 强杀 ffmpeg 及其子进程；无论正常结束还是断开都必须调用 */
    private void destroy(Process process)
    {
        if (process == null || !process.isAlive())
        {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try
        {
            process.waitFor(3, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void acquire()
    {
        synchronized (active)
        {
            if (active.get() >= getMax())
            {
                throw MediaException.tooManyRequests("转码忙，请稍后再试");
            }
            active.incrementAndGet();
        }
    }

    private void release()
    {
        active.decrementAndGet();
    }

    private List<String> buildCommand(Path file, Preset preset, int start)
    {
        // min(iw,…) 保证小片源不放大；force_divisible_by=2 满足 yuv420p 偶数尺寸
        String scale = "scale=w='min(iw," + preset.width + ")':h='min(ih," + preset.height
                + ")':force_original_aspect_ratio=decrease:force_divisible_by=2";
        List<String> command = new ArrayList<>();
        command.add(mediaProperties.getFfmpeg());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        // -ss 放在 -i 之前：输入侧快速 seek，避免从头解码
        command.add("-ss");
        command.add(String.valueOf(start));
        command.add("-i");
        command.add(file.toString());
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-sn");
        command.add("-dn");
        command.add("-vf");
        command.add(scale);
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-tune");
        command.add("zerolatency");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-b:v");
        command.add(preset.videoBitrate);
        command.add("-maxrate");
        command.add(preset.maxRate);
        command.add("-bufsize");
        command.add(preset.bufSize);
        command.add("-g");
        command.add("48");
        command.add("-keyint_min");
        command.add("48");
        command.add("-sc_threshold");
        command.add("0");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(preset.audioBitrate);
        command.add("-movflags");
        command.add("frag_keyframe+empty_moov+default_base_moof");
        command.add("-f");
        command.add("mp4");
        command.add("pipe:1");
        return command;
    }

    private static int parseStart(String t)
    {
        if (StringUtils.isEmpty(t))
        {
            return 0;
        }
        try
        {
            double value = Double.parseDouble(t.trim());
            if (!Double.isFinite(value) || value <= 0)
            {
                return 0;
            }
            return (int) Math.floor(value);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /** 单一清晰度档位的转码参数 */
    private static final class Preset
    {
        /** 缩放上限宽度 */
        private final int width;

        /** 缩放上限高度 */
        private final int height;

        /** 目标视频码率 */
        private final String videoBitrate;

        /** 峰值视频码率 */
        private final String maxRate;

        /** 码率缓冲 */
        private final String bufSize;

        /** 音频码率 */
        private final String audioBitrate;

        private Preset(int width, int height, String videoBitrate, String maxRate, String bufSize,
                String audioBitrate)
        {
            this.width = width;
            this.height = height;
            this.videoBitrate = videoBitrate;
            this.maxRate = maxRate;
            this.bufSize = bufSize;
            this.audioBitrate = audioBitrate;
        }
    }
}
