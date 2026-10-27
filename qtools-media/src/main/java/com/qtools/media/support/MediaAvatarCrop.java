package com.qtools.media.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 头像裁剪参数（P5.55）：解析 / 归一 / 按参数裁出缩略图
 *
 * <h2>为什么是「归一化比例」而不是像素</h2>
 *
 * <p>裁剪框是用户在一张<b>显示尺寸</b>的图上拖出来的，同一张图在卡片 / 详情页 / 手机上的像素尺寸全不同；
 * 存像素就等于把图片的当前分辨率焊进数据库，缩略图一重建（build-thumbs 换尺寸）裁剪框就偏了。
 * 存 0..1 的比例则与分辨率无关：{@code 0.2100,0.1300,0.5600,0.5600} = 左上角在 21%/13%、宽高各占 56%。</p>
 *
 * <h2>落库形状（向后兼容）</h2>
 *
 * <p>{@code a164:4} —— 老形状，整图（一个字都没变，老行照旧能读）；<br>
 * {@code a164:4#crop=0.2100,0.1300,0.5600,0.5600} —— 带裁剪。解析端一律「没有 #crop 就是整图」。</p>
 *
 * <h2>产出缓存</h2>
 *
 * <p>裁剪结果写到 {@code <thumbs>/avcrop/<sha1 前 20>.jpg}，键里带上<b>源文件的 size + mtime</b>：
 * 缩略图被重建后键就变了，不会端出上一版的裁图（比「记一个 TTL」更准，也不需要清理任务）。</p>
 *
 * @author qtools
 */
public final class MediaAvatarCrop
{
    private static final Logger log = LoggerFactory.getLogger(MediaAvatarCrop.class);

    /** ref 里的裁剪分隔符：{@code a164:4#crop=x,y,w,h} */
    public static final String MARK = "#crop=";

    /** 裁剪缓存目录（thumbs 下） */
    public static final String CACHE_DIR = "avcrop";

    /** 与 media_person.avatar_ref VARCHAR(512) 对齐：四个小数最多各 8 字符 */
    private static final int PARTS = 4;

    /** 最小边长占比：小于 2% 的框在缩略图上只剩几个像素，裁出来是糊的，直接判非法 */
    private static final double MIN_RATIO = 0.02;

    private MediaAvatarCrop()
    {
    }

    /**
     * 归一化裁剪参数
     *
     * @param raw {@code "x,y,w,h"}（可带 {@code crop=} 前缀），全部为 0..1 的比例
     * @return {@code "x,y,w,h"}（各 4 位小数）；null = 整图（空串也是整图）
     * @throws IllegalArgumentException 格式或范围不对（调用方转成 400）
     */
    public static String normalize(String raw)
    {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty())
        {
            return null;
        }
        // 两种入口都吃：ref 尾巴 "#crop=x,y,w,h" 与纯 "crop=x,y,w,h" / "x,y,w,h"
        if (text.startsWith("#"))
        {
            text = text.substring(1).trim();
        }
        if (text.regionMatches(true, 0, "crop=", 0, 5))
        {
            text = text.substring(5).trim();
        }
        String[] parts = text.split(",");
        if (parts.length != PARTS)
        {
            throw new IllegalArgumentException("crop 必须是 x,y,w,h 四个 0..1 的比例，本次: " + raw);
        }
        double[] v = new double[PARTS];
        for (int i = 0; i < PARTS; i++)
        {
            try
            {
                v[i] = Double.parseDouble(parts[i].trim());
            }
            catch (NumberFormatException e)
            {
                throw new IllegalArgumentException("crop 第 " + (i + 1) + " 个值不是数字: " + parts[i]);
            }
            if (!(v[i] >= 0d && v[i] <= 1d))
            {
                throw new IllegalArgumentException("crop 每个值都必须在 0..1 之间，本次第 " + (i + 1) + " 个是 " + parts[i]);
            }
        }
        double x = v[0];
        double y = v[1];
        double w = v[2];
        double h = v[3];
        if (w < MIN_RATIO || h < MIN_RATIO)
        {
            throw new IllegalArgumentException("crop 的宽高至少要占 " + (int) (MIN_RATIO * 100) + "%");
        }
        if (x + w > 1.0001d || y + h > 1.0001d)
        {
            throw new IllegalArgumentException("crop 超出图片边界（x+w 或 y+h 大于 1）: " + raw);
        }
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", x, y, w, h);
    }

    /** ref 里带没带裁剪（给「撤销裁剪」按钮用） */
    public static boolean hasCrop(String ref)
    {
        return ref != null && ref.toLowerCase(Locale.ROOT).contains(MARK);
    }

    /**
     * 取 ref 的主体段（{@code #} 之前）：{@code a164:4#crop=...} → {@code a164:4}
     *
     * <p>P5.61：任何要按 {@code <albumId>:<seq>} 解析 ref 的地方都必须先过这一层，
     * 否则 {@code Integer.parseInt("4#crop=...")} 会抛 NumberFormatException → 500。</p>
     */
    public static String bodyOf(String ref)
    {
        if (ref == null)
        {
            return null;
        }
        int hash = ref.indexOf('#');
        return hash < 0 ? ref : ref.substring(0, hash);
    }

    /** 从 {@code a164:4#crop=...} 里取 {@code #} 之后的裁剪串（没有则 null），不做校验 */
    public static String tailOf(String ref)
    {
        if (ref == null)
        {
            return null;
        }
        int hash = ref.indexOf('#');
        return hash < 0 ? null : ref.substring(hash);
    }

    /**
     * 按裁剪参数产出（并缓存）一张裁好的 jpg
     *
     * @param src        源图（thumbs 下的缩略图 / 预览图）
     * @param crop       已归一的 {@code x,y,w,h}（null = 整图，直接返回 src）
     * @param thumbsRoot 缩略图根（{@code qtools.media.thumbsPath}）
     * @param cacheKey   缓存键前缀（一般是 {@code "<albumId>/<seq>"}）
     * @return 可以直接交给响应体的文件
     */
    public static Path render(Path src, String crop, Path thumbsRoot, String cacheKey)
    {
        if (crop == null || crop.isEmpty())
        {
            return src;
        }
        Path out = null;
        try
        {
            long size = Files.size(src);
            long mtime = Files.getLastModifiedTime(src).toMillis();
            String digest = sha1(cacheKey + "|" + crop + "|" + size + "|" + mtime);
            Path dir = thumbsRoot.resolve(CACHE_DIR);
            out = dir.resolve(digest + ".jpg");
            if (Files.isRegularFile(out) && Files.size(out) > 0)
            {
                return out;
            }
            Files.createDirectories(dir);
            BufferedImage image = ImageIO.read(src.toFile());
            if (image == null)
            {
                throw new IOException("源图不是可解码的图片: " + src);
            }
            BufferedImage cropped = cut(image, crop);
            writeJpeg(cropped, out);
            return out;
        }
        catch (IOException e)
        {
            // 裁剪失败不能让头像 404：退回整图，用户看到的是「没裁」，不是「没有头像」
            log.warn("头像裁剪失败，退回整图 src={} crop={} err={}", src, crop, e.getMessage());
            return src;
        }
    }

    /**
     * 对内存里的图片字节按裁剪参数现裁一张 jpg（P5.64：取帧接口的 {@code ?crop=}）
     *
     * <p>与 {@link #render} 的分工：{@code render} 是「磁盘上的缩略图 → 裁好的缓存文件」，
     * 这里处理的是**取帧返回的 jpeg 字节**（本来就不落盘：URL 里带秒 + crop 已经是内容寻址，
     * 缓存交给浏览器 / 网关，不再多一份磁盘垃圾）。</p>
     *
     * <p>与 {@code render} 同一条失败策略：裁剪失败退回原字节 —— 头像宁可「没裁」，
     * 也不能因为一次裁剪异常整条挂掉。</p>
     *
     * @param src  源图字节（jpeg / png 都能解码）
     * @param crop 已归一的 {@code x,y,w,h}；null / 空串 = 原样返回（整图）
     */
    public static byte[] cropJpeg(byte[] src, String crop)
    {
        if (src == null || src.length == 0 || crop == null || crop.isEmpty())
        {
            return src;
        }
        try
        {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(src));
            if (image == null)
            {
                throw new IOException("源字节不是可解码的图片");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, src.length));
            writeJpeg(cut(image, crop), out);
            return out.toByteArray();
        }
        catch (IOException e)
        {
            log.warn("帧裁剪失败，退回原图 crop={} err={}", crop, e.getMessage());
            return src;
        }
    }

    /**
     * 按比例切一刀
     *
     * <p>边界一律夹紧（{@code x+w=1} 时 {@code round} 可能算出 1 个像素的溢出，
     * 越界会让 {@link BufferedImage#getSubimage} 直接抛异常）。</p>
     */
    private static BufferedImage cut(BufferedImage image, String crop)
    {
        String[] parts = crop.split(",");
        double x = Double.parseDouble(parts[0]);
        double y = Double.parseDouble(parts[1]);
        double w = Double.parseDouble(parts[2]);
        double h = Double.parseDouble(parts[3]);
        int maxW = image.getWidth();
        int maxH = image.getHeight();
        int sx = clamp((int) Math.round(x * maxW), 0, maxW - 1);
        int sy = clamp((int) Math.round(y * maxH), 0, maxH - 1);
        int sw = clamp((int) Math.round(w * maxW), 1, maxW - sx);
        int sh = clamp((int) Math.round(h * maxH), 1, maxH - sy);
        return image.getSubimage(sx, sy, sw, sh);
    }

    private static int clamp(int value, int min, int max)
    {
        if (value < min)
        {
            return min;
        }
        return value > max ? max : value;
    }

    /** 写 jpg（显式 0.9 质量：ImageIO 默认 0.75 裁小图会明显发糊） */
    private static void writeJpeg(BufferedImage image, Path out) throws IOException
    {
        try (OutputStream raw = Files.newOutputStream(out))
        {
            writeJpeg(image, raw);
        }
    }

    /** 写 jpg 到任意输出流（P5.64：内存裁剪走这里） */
    private static void writeJpeg(BufferedImage image, OutputStream raw) throws IOException
    {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(raw))
        {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed())
            {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.9f);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        }
        finally
        {
            writer.dispose();
        }
    }

    private static String sha1(String text) throws IOException
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.substring(0, 20);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IOException("SHA-1 不可用", e);
        }
    }
}
