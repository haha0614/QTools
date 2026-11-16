package com.qtools.media.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片解码（P6.99）—— ImageIO 优先，解不了的格式（WebP）交给 ffmpeg
 *
 * <h2>为什么需要这一层</h2>
 *
 * <p>媒体的原图里有 WebP（写真套图大量是 {@code .webp}），而 JRE 自带的 ImageIO
 * <b>没有 WebP 解码器</b>。以前这条路上有两个静默的坑，症状都是「头像是默认首字、卡片没有背景」：</p>
 *
 * <ol>
 *   <li>{@link MediaAvatarCrop#render} 读不出源图 → 退回<b>原文件字节</b>，
 *       而 HTTP 早在更外层写死了 {@code Content-Type: image/jpeg} —— 于是浏览器收到的是
 *       「说是 JPEG 其实是 WebP」的一段字节，解码失败、头像变首字占位；</li>
 *   <li>{@code MediaPersonCardBgServiceImpl} 直接 {@code ImageIO.read} → null → 404，
 *       人物卡背景整层不渲染。</li>
 * </ol>
 *
 * <p>所以解码收敛到这一处：能 ImageIO 就是零成本，不能就走本机 ffmpeg
 * （{@code qtools.media.ffmpeg}，取帧 / 转码早就依赖它，不新增任何 Java 依赖）。
 * ffmpeg 缺席 / 超时 / 出空 → 返回 {@code null}，调用方仍按「解不出来」处理，行为不比改动前更差。</p>
 *
 * @author qtools
 */
public final class MediaImageDecode
{
    private static final Logger log = LoggerFactory.getLogger(MediaImageDecode.class);

    /** ffmpeg 解码超时：单张图解码是百毫秒级，15s 已经是「卡住了」而不是「慢」 */
    private static final int TIMEOUT_MS = 15000;

    /** 解码上限边长：头像 150–200 CSS px、人物卡背景最宽 1200 px，2400 px 足够且不吃堆内存 */
    private static final int MAX_DIM = 2400;

    private MediaImageDecode()
    {
    }

    /**
     * 读一张图（ImageIO 优先，失败走 ffmpeg）
     *
     * @param src    源文件（jpeg / png / webp / …）
     * @param ffmpeg ffmpeg 可执行文件路径（为空 = 不启用回退）
     * @return 位图；两种解码器都失败返回 {@code null}（<b>不抛异常</b>，调用方各自决定降级）
     */
    public static BufferedImage read(Path src, String ffmpeg)
    {
        if (src == null || !Files.isRegularFile(src))
        {
            return null;
        }
        BufferedImage direct = readImageIo(src);
        if (direct != null)
        {
            return direct;
        }
        return readFfmpeg(src, ffmpeg);
    }

    /**
     * 读一段图片字节（同上，先落临时文件再交给 ffmpeg —— 比 {@code -i pipe:0} 少一类兼容问题）
     *
     * @param bytes  图片字节
     * @param ffmpeg ffmpeg 路径（为空 = 不启用回退）
     * @return 位图；解不出来返回 {@code null}
     */
    public static BufferedImage read(byte[] bytes, String ffmpeg)
    {
        if (bytes == null || bytes.length == 0)
        {
            return null;
        }
        try
        {
            BufferedImage direct = ImageIO.read(new ByteArrayInputStream(bytes));
            if (direct != null)
            {
                return direct;
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("ImageIO 解码字节失败（尝试 ffmpeg）：{}", e.getMessage());
        }
        if (ffmpeg == null || ffmpeg.trim().isEmpty())
        {
            return null;
        }
        Path temp = null;
        try
        {
            // 后缀给 .bin：ffmpeg 靠内容嗅探，不靠扩展名
            temp = Files.createTempFile("qtools-img-", ".bin");
            Files.write(temp, bytes);
            return readFfmpeg(temp, ffmpeg);
        }
        catch (IOException e)
        {
            log.debug("写临时文件失败，无法用 ffmpeg 解码：{}", e.getMessage());
            return null;
        }
        finally
        {
            if (temp != null)
            {
                try
                {
                    Files.deleteIfExists(temp);
                }
                catch (IOException ignored)
                {
                    /* 临时文件删不掉不影响本次解码 */
                }
            }
        }
    }

    /** ImageIO：先按源采样读（大图省内存），不支持采样的格式退回普通读 */
    private static BufferedImage readImageIo(Path src)
    {
        try (ImageInputStream in = ImageIO.createImageInputStream(src.toFile()))
        {
            if (in == null)
            {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext())
            {
                return null;
            }
            ImageReader reader = readers.next();
            try
            {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                int step = Math.max(1, (int) Math.ceil((double) Math.max(w, h) / Math.max(1, MAX_DIM)));
                javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            }
            finally
            {
                reader.dispose();
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("ImageIO 按源采样读图失败（尝试普通读）{}：{}", src, e.getMessage());
            try
            {
                return ImageIO.read(src.toFile());
            }
            catch (IOException again)
            {
                return null;
            }
        }
    }

    /** ffmpeg 解码一帧成 JPEG（stdout 管道），再用 ImageIO 读回来 */
    private static BufferedImage readFfmpeg(Path src, String ffmpeg)
    {
        if (ffmpeg == null || ffmpeg.trim().isEmpty())
        {
            return null;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        // 服务端 stdin 不是终端：不加 -nostdin 时 ffmpeg 可能读走字节并卡住（与取帧同一条坑）
        cmd.add("-nostdin");
        cmd.add("-v");
        cmd.add("error");
        cmd.add("-i");
        cmd.add(src.toString());
        cmd.add("-frames:v");
        cmd.add("1");
        // 等比缩到 2400 方框内；用命名参数避免 min() 里的逗号被当成分隔符
        cmd.add("-vf");
        cmd.add("scale=w=" + MAX_DIM + ":h=" + MAX_DIM + ":force_original_aspect_ratio=decrease");
        cmd.add("-f");
        cmd.add("image2pipe");
        cmd.add("-vcodec");
        cmd.add("mjpeg");
        cmd.add("pipe:1");
        Process process = null;
        try
        {
            process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            byte[] out = readAll(process.getInputStream());
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                process.destroyForcibly();
                log.warn("ffmpeg 解码超时 src={}", src);
                return null;
            }
            if (out.length == 0)
            {
                log.debug("ffmpeg 解码无输出 src={} exit={}", src, process.exitValue());
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(out));
        }
        catch (IOException | InterruptedException | RuntimeException e)
        {
            if (e instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            log.warn("ffmpeg 解码失败 src={} err={}", src, e.getMessage());
            return null;
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                process.destroyForcibly();
            }
        }
    }

    /** 读干一个流（不读会把子进程顶住） */
    private static byte[] readAll(InputStream in) throws IOException
    {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0)
        {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
