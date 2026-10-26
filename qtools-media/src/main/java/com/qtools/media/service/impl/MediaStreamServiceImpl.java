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
import com.qtools.media.support.MediaTranscodeCommand;
import com.qtools.media.support.MediaTranscodeProbe;

/**
 * 实时转码 服务实现
 *
 * <p>与 Node 版 /api/stream 行为对齐：</p>
 * <ul>
 *   <li>并发上限（默认 2），超出立刻 429，名额在启动 ffmpeg 前占用；</li>
 *   <li>先拿到首字节再回 200，超时（15s）回 504，避免客户端拿到空响应；</li>
 *   <li>客户端断开（写失败）或正常结束都必须 kill 进程，否则僵尸进程会占满名额；</li>
 *   <li>ffmpeg 缺失回 503；</li>
 *   <li><b>硬件转码（P5-B）</b>：编码器由 {@link MediaTranscodeProbe} 探测决定（auto 时
 *       h264_nvenc → h264_qsv → libx264），硬件进程失败时记 WARN、把该编码器标记为不可用，
 *       并<b>用软件参数重试一次</b>——用户宁可慢一点，也不想看黑屏。</li>
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
    private static final Map<String, MediaTranscodeCommand.Profile> PRESETS = new LinkedHashMap<>();

    static
    {
        PRESETS.put("720", new MediaTranscodeCommand.Profile(1280, 720, "1500k", "1700k", "3000k", "96k"));
        PRESETS.put("480", new MediaTranscodeCommand.Profile(854, 480, "700k", "800k", "1500k", "64k"));
        PRESETS.put("360", new MediaTranscodeCommand.Profile(640, 360, "400k", "450k", "900k", "48k"));
    }

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaFileService mediaFileService;

    /** 硬件编码器探测（懒加载 + 内存缓存）；命令构造与失败回退都以它给出的编码器为准 */
    @Autowired
    private MediaTranscodeProbe mediaTranscodeProbe;

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
    public Map<String, Object> getTranscoderInfo()
    {
        return mediaTranscodeProbe.describe();
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
        MediaTranscodeCommand.Profile profile = q == null ? null : PRESETS.get(q);
        if (profile == null)
        {
            throw MediaException.badRequest("bad q");
        }
        // P5.17：起播点允许小数秒（帧级落点），ffmpeg 用精确 -ss；缺省/非法值仍是 0
        double start = parseStartSec(t);
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
        Started started = null;
        String endReason = "unknown";
        long sent = 0;
        try
        {
            started = startAndFirstByte(file, profile, start);
            Process process = started.process;
            byte first = started.firstByte;

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
            destroy(started == null ? null : started.process);
            release();
            log.info("媒体转码结束 q={} t={} file={} enc={} sent={}KB end={} cost={}ms", q,
                    MediaTranscodeCommand.formatStart(start), p,
                    started == null ? "-" : started.encoder, sent / 1024, endReason,
                    System.currentTimeMillis() - begin);
        }
    }

    // ------------------------------------------------------------------ 进程

    /**
     * 启动 ffmpeg 并拿到首字节；硬件编码器失败时用软件编码器<b>重试一次</b>（P5-B）
     *
     * <p>「失败」= 进程起不来 / 首字节超时 / 进程提前退出（{@link #readFirstByte} 把后两种都归成
     * 非正常返回）。此时硬件这条路已经证明在本机不成立：先 {@code markUnavailable} 让本进程后续请求
     * 直接走软件（不必每个请求都失败一次），再拿软件参数重跑一遍。软件再失败就是真的失败，
     * 异常原样上抛（504 / 500），不无限重试。</p>
     */
    private Started startAndFirstByte(Path file, MediaTranscodeCommand.Profile profile, double start) throws IOException
    {
        String encoder = mediaTranscodeProbe.effective();
        Process process = startProcess(file, profile, start, encoder);
        try
        {
            return new Started(process, readFirstByte(process), encoder);
        }
        catch (MediaException e)
        {
            destroy(process);
            return retrySoftware(file, profile, start, encoder, e);
        }
        catch (IOException e)
        {
            destroy(process);
            return retrySoftware(file, profile, start, encoder, e);
        }
    }

    /** 回退分支：非硬件编码器直接把原异常抛回去；硬件才降级重试一次 */
    private Started retrySoftware(Path file, MediaTranscodeCommand.Profile profile, double start, String encoder,
            Exception cause) throws IOException
    {
        if (!MediaTranscodeCommand.isHardware(encoder))
        {
            throw rethrow(cause);
        }
        log.warn("硬件编码器 {} 转码失败（{}），回退软件编码器重试一次 file={} q={}x{}", encoder,
                cause.getMessage(), file.getFileName(), profile.width, profile.height);
        mediaTranscodeProbe.markUnavailable(encoder);
        String fallback = mediaTranscodeProbe.effective();
        Process retry = startProcess(file, profile, start, fallback);
        try
        {
            return new Started(retry, readFirstByte(retry), fallback);
        }
        catch (IOException e)
        {
            destroy(retry);
            throw e;
        }
        catch (MediaException e)
        {
            destroy(retry);
            throw e;
        }
    }

    /** 把捕获到的异常按原类型抛出去（方法签名只声明 IOException，业务异常是运行时异常） */
    private static RuntimeException rethrow(Exception cause) throws IOException
    {
        if (cause instanceof IOException)
        {
            throw (IOException) cause;
        }
        if (cause instanceof RuntimeException)
        {
            throw (RuntimeException) cause;
        }
        throw new IOException(cause);
    }

    private Process startProcess(Path file, MediaTranscodeCommand.Profile profile, double start, String encoder)
            throws IOException
    {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(file, profile, start, encoder));
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

    /**
     * 拼命令：整条命令的构造在 {@link MediaTranscodeCommand}（硬件/软件的唯一分叉点集中在那里，
     * 好处是能离线逐字打印核验）。本方法只负责把配置里的编码器与档位喂进去。
     */
    private List<String> buildCommand(Path file, MediaTranscodeCommand.Profile profile, double start, String encoder)
    {
        return MediaTranscodeCommand.build(mediaProperties.getFfmpeg(), encoder,
                mediaProperties.getTranscode().getPreset(), mediaProperties.getTranscode().getCq(), profile, file,
                start);
    }

    /**
     * 解析 {@code t} 起播秒（P5.17：小数秒）
     *
     * <p>P5.16 之前这里 {@code (int) Math.floor(value)} 把小数直接丢掉 —— 前端按帧栅格算出的
     * 3.4s 到后端变成 3.000s，再叠加转码流 seekable 只有 [0,0]（浏览器没法在流内补 seek），
     * 于是「松手停在最近的整秒」。现在原样保留到微秒，交给 {@link MediaTranscodeCommand#formatStart}。</p>
     *
     * <p>兼容性：{@code t=3}（老前端）→ 3.0 → {@code -ss 3}，与改动前逐字一致；
     * 空 / 非数字 / 负数 / 非有限值一律 0。</p>
     */
    private static double parseStartSec(String t)
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
            return Math.round(value * 1_000_000d) / 1_000_000d;
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    /** 一次转码的启动结果：进程本身 + 已读到的首字节 + 真正用的编码器（日志与回退诊断用） */
    private static final class Started
    {
        private final Process process;

        private final byte firstByte;

        private final String encoder;

        private Started(Process process, byte firstByte, String encoder)
        {
            this.process = process;
            this.firstByte = firstByte;
            this.encoder = encoder;
        }
    }
}
