package com.qtools.media.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaFrameService;
import com.qtools.media.support.MediaFrameCommand;

/**
 * 视频实时取帧 服务实现（P5.15）
 *
 * <p>三层保护，缺一层都会被鼠标移动打成请求风暴：</p>
 * <ol>
 *   <li><b>磁盘缓存</b>：{@code <thumbs>/frames/<hash>/<秒>.jpg}，命中直接读盘，不启动 ffmpeg；
 *       URL 按秒寻址 → 内容永不变化 → 响应带 {@code immutable} 长缓存，浏览器连回源都不做；</li>
 *   <li><b>同键去重</b>：同一秒的并发请求只让一个去解码，其余等它落盘后直接读（避免 5 个请求
 *       同时为同一秒各起 1 个 ffmpeg）；</li>
 *   <li><b>并发上限</b>：{@code qtools.media.maxFrame}（默认 4）个 permit，超时排队 2 秒仍拿不到
 *       就 429 —— 宁可让前端继续用精灵图，也不让 8 个 ffmpeg 抢满 CPU。</li>
 * </ol>
 *
 * @author qtools
 */
@Service
public class MediaFrameServiceImpl implements IMediaFrameService
{
    private static final Logger log = LoggerFactory.getLogger(MediaFrameServiceImpl.class);

    /** 缓存子目录名（build-thumbs.js --gc 会按 v/s/pv2 同一套规则回收孤儿） */
    private static final String FRAME_DIR = "frames";

    private static final String JPG_SUFFIX = ".jpg";

    /** 视频 id：与 resolveSprite 同一套白名单，杜绝路径分隔符 */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 内容哈希：16 位 hex（oshash 前 16 位）；脏数据不进文件路径 */
    private static final Pattern SAFE_CONTENT_HASH = Pattern.compile("^[0-9a-f]{6,64}$");

    /** 单帧解码的硬超时：正常 100-400ms，20 秒已经是「这条命令彻底卡住」 */
    private static final long FRAME_TIMEOUT_MS = 20_000L;

    /** 排队等 permit 的时长：超过就 429（前端静默回退精灵图，用户无感） */
    private static final long QUEUE_TIMEOUT_MS = 2_000L;

    /** stderr 只留尾部，供 WARN 诊断 */
    private static final int STDERR_TAIL = 4_000;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private MediaVideoMapper mediaVideoMapper;

    /** 取帧并发名额（懒初始化：{@code qtools.media.maxFrame}，改配置后重启生效） */
    private volatile Semaphore permits;

    /** 实际生效的并发上限（只用于日志，避免反查 Semaphore） */
    private volatile int permitsLimit;

    /** 同一秒的并发请求去重：键 {@code 桶/秒}，值只是一个锁对象 */
    private final ConcurrentHashMap<String, Object> inflight = new ConcurrentHashMap<>();

    /** 并发名额：首次用到时按配置创建（{@code @Autowired} 字段在构造之后才注入，不能写字段初始化） */
    private Semaphore permits()
    {
        Semaphore current = permits;
        if (current != null)
        {
            return current;
        }
        synchronized (this)
        {
            if (permits == null)
            {
                permitsLimit = Math.max(1, mediaProperties.getMaxFrame());
                permits = new Semaphore(permitsLimit);
            }
            return permits;
        }
    }

    @Override
    public boolean isAvailable()
    {
        String ffmpeg = mediaProperties.getFfmpeg();
        if (StringUtils.isEmpty(ffmpeg))
        {
            return false;
        }
        try
        {
            return Files.isRegularFile(Path.of(ffmpeg.trim()));
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    @Override
    public int durationOf(String videoId)
    {
        MediaVideo video = selectVideo(videoId);
        if (video == null || video.getDur() == null)
        {
            return -1;
        }
        try
        {
            return (int) Math.floor(video.getDur().doubleValue());
        }
        catch (RuntimeException e)
        {
            return -1;
        }
    }

    @Override
    public byte[] frame(String videoId, int sec)
    {
        if (videoId == null || !SAFE_ID.matcher(videoId).matches())
        {
            throw MediaException.forbidden();
        }
        MediaVideo video = selectVideo(videoId);
        if (video == null || StringUtils.isEmpty(video.getRelPath()))
        {
            throw MediaException.notFound();
        }
        int target = Math.max(0, sec);
        Path cacheFile = cacheFile(video, target);
        byte[] cached = readCached(cacheFile);
        if (cached != null)
        {
            // 命中即返回：这条路径**不启动 ffmpeg**（debug 级即可验证「第二次请求没有解码」）。
            // 也不碰源文件与 ffmpeg 可用性 —— 缓存已经落盘的帧，源被挪走 / ffmpeg 暂时没配也照发。
            log.debug("取帧命中缓存 id={} t={} file={}", videoId, target, cacheFile);
            return cached;
        }
        if (!isAvailable())
        {
            log.warn("取帧失败 id={} t={}：ffmpeg 未配置或不存在（qtools.media.ffmpeg）", videoId, sec);
            throw new MediaException(503, "ffmpeg 不可用，无法取帧（请配置 qtools.media.ffmpeg / QTOOLS_MEDIA_FFMPEG）");
        }
        // 越权 / 源文件不存在：在占用并发名额之前判掉
        Path source = mediaFileService.resolveMedia(video.getRelPath(), video.getRootId());

        String key = cacheFile.getParent().getFileName().toString() + '/' + target;
        Object lock = inflight.computeIfAbsent(key, k -> new Object());
        try
        {
            synchronized (lock)
            {
                // 双检：等锁期间别的请求可能已经把这帧生成好了
                cached = readCached(cacheFile);
                if (cached != null)
                {
                    return cached;
                }
                byte[] bytes = decode(videoId, source, target);
                writeCache(cacheFile, bytes);
                return bytes;
            }
        }
        finally
        {
            inflight.remove(key, lock);
        }
    }

    /**
     * 真正调 ffmpeg 解一帧
     *
     * <p>失败一律 404（「这一秒没有可显示的帧」）并记 WARN：解码失败不是服务端内部错误，
     * 前端只该安静回退精灵图。ffmpeg 起不来这类环境问题走 503。</p>
     */
    private byte[] decode(String videoId, Path source, int sec)
    {
        acquire(videoId, sec);
        long begin = System.currentTimeMillis();
        List<String> command = MediaFrameCommand.build(mediaProperties.getFfmpeg().trim(), source, sec);
        Process process = null;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            process = builder.start();
            StringBuilder stderr = drainStderr(process);
            // 输出必须在**独立线程**里收：若在调用线程同步 readAll，一条卡住的 ffmpeg 会永久占住
            // 一个并发名额（超时判定根本走不到），4 次之后再怎么请求都只能 429 —— 服务不会自愈。
            ByteArrayOutputStream sink = new ByteArrayOutputStream(64 * 1024);
            Thread reader = startStdoutReader(process, sink);
            boolean finished = process.waitFor(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished)
            {
                destroy(process);
                log.warn("取帧超时 id={} t={} cost={}ms", videoId, sec, System.currentTimeMillis() - begin);
                throw MediaException.notFound("frame timeout");
            }
            // 进程已退出 → 管道已关 → 读线程必然很快结束；join 不到也只说明输出不完整，下面按空字节处理
            reader.join(2000L);
            byte[] bytes = sink.toByteArray();
            int exit = process.exitValue();
            if (exit != 0 || bytes.length == 0)
            {
                log.warn("取帧失败 id={} t={} exit={} bytes={} stderr={}", videoId, sec, exit, bytes.length,
                        abbreviate(stderr));
                throw MediaException.notFound("no frame at " + sec + "s");
            }
            log.debug("取帧 id={} t={} bytes={} cost={}ms", videoId, sec, bytes.length,
                    System.currentTimeMillis() - begin);
            return bytes;
        }
        catch (IOException e)
        {
            log.warn("取帧失败 id={} t={}：无法启动 ffmpeg（{}）", videoId, sec, e.getMessage());
            throw new MediaException(503, "ffmpeg 启动失败");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new MediaException(503, "取帧被中断");
        }
        finally
        {
            destroy(process);
            release();
        }
    }

    /** 缓存文件：{@code <thumbs>/frames/<content_hash|videoId>/<秒>.jpg}（按内容哈希分桶） */
    private Path cacheFile(MediaVideo video, int sec)
    {
        String bucket = bucketOf(video);
        return mediaFileService.thumbsRoot().resolve(FRAME_DIR).resolve(bucket).resolve(sec + JPG_SUFFIX);
    }

    /**
     * 分桶名：内容哈希优先（改名 / 移动不变），老库没回填哈希时退回 video id
     *
     * <p>与 v/ s/ pv2 同一套命名，{@code --gc} 才能按同一条规则判孤儿。</p>
     */
    private String bucketOf(MediaVideo video)
    {
        String hash = video.getContentHash();
        if (hash != null)
        {
            String trimmed = hash.trim().toLowerCase();
            if (SAFE_CONTENT_HASH.matcher(trimmed).matches())
            {
                return trimmed;
            }
        }
        return video.getId();
    }

    private MediaVideo selectVideo(String videoId)
    {
        try
        {
            return mediaVideoMapper.selectVideoById(videoId);
        }
        catch (RuntimeException e)
        {
            // 读库失败按「没有这部片」处理，别让取帧接口跟着库一起 500
            log.warn("取帧查库失败 id={}：{}", videoId, e.getMessage());
            return null;
        }
    }

    private byte[] readCached(Path file)
    {
        try
        {
            if (!Files.isRegularFile(file))
            {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length == 0 ? null : bytes;
        }
        catch (IOException | RuntimeException e)
        {
            // 读缓存失败不致命：当作未命中，重新解一帧（写盘会覆盖掉坏文件）
            log.debug("取帧缓存读取失败 {}：{}", file, e.getMessage());
            return null;
        }
    }

    /** 落盘：先写同目录临时文件再原子改名，避免并发读到半张图 */
    private void writeCache(Path file, byte[] bytes)
    {
        Path dir = file.getParent();
        Path tmp = null;
        try
        {
            Files.createDirectories(dir);
            tmp = dir.resolve(file.getFileName() + ".tmp" + Thread.currentThread().getId());
            Files.write(tmp, bytes);
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException | RuntimeException e)
        {
            // 缓存写不进去只影响下一次命中，本次响应照发
            log.warn("取帧缓存写入失败 {}：{}", file, e.getMessage());
            if (tmp != null)
            {
                try
                {
                    Files.deleteIfExists(tmp);
                }
                catch (IOException ignored)
                {
                    // 临时文件删不掉也无所谓：--gc 按 .jpg 扩展名判定，不会把它当孤儿保留成垃圾
                }
            }
        }
    }

    private void acquire(String videoId, int sec)
    {
        try
        {
            if (!permits().tryAcquire(QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                log.warn("取帧并发超限 id={} t={} max={}", videoId, sec, permitsLimit);
                throw MediaException.tooManyRequests("取帧忙，请稍后再试");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new MediaException(503, "取帧被中断");
        }
    }

    private void release()
    {
        permits().release();
    }

    /** 排空 stderr（避免管道写满把 ffmpeg 卡死），只留尾部供 WARN 用 */
    private StringBuilder drainStderr(Process process)
    {
        StringBuilder tail = new StringBuilder();
        Thread thread = new Thread(() -> {
            try (InputStream stderr = process.getErrorStream())
            {
                byte[] buffer = new byte[2048];
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
                // 进程被销毁时管道会关掉，属正常
            }
        }, "media-frame-stderr");
        thread.setDaemon(true);
        thread.start();
        return tail;
    }

    /** 在独立线程里把 ffmpeg 的 stdout 收进 sink（进程被 destroy 时管道关闭，线程自然结束） */
    private Thread startStdoutReader(Process process, ByteArrayOutputStream sink)
    {
        Thread thread = new Thread(() -> {
            try (InputStream in = process.getInputStream())
            {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1)
                {
                    sink.write(buffer, 0, read);
                }
            }
            catch (IOException e)
            {
                // 超时强杀会打断读取，属正常路径
            }
        }, "media-frame-stdout");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

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

    private static String abbreviate(StringBuilder stderr)
    {
        String text = stderr == null ? "" : stderr.toString().trim();
        return text.length() > 400 ? text.substring(0, 400) : text;
    }
}
