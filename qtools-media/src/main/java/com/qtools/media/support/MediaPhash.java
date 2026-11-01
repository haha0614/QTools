package com.qtools.media.support;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 感知指纹（dHash，64 位）——「相似图片」的判定依据（P6.77）
 *
 * <p>为什么不是 content_hash：那个是文件字节的 MD5，只能发现<b>逐字节相同</b>的重复。
 * 同一张图被重新压缩 / 缩放 / 转码后字节变了，MD5 完全不同，于是永远进不了重复列表
 * （实测：019_2023写真合集_0003 与 130_2023全裸写真_0003 只差 142 字节，视觉上是同一张）。</p>
 *
 * <p>算法（与常见 dHash 一致，实现刻意保持简单、可复算）：</p>
 * <ol>
 *   <li>读 800 宽缩略图（{@code thumbs/p8/<content_hash>.jpg}），转灰度；</li>
 *   <li>重采样到 9×8（区域平均），逐行比较相邻两点：左 &lt; 右 记 1，否则记 0；</li>
 *   <li>得到 64 位；两个指纹的<b>汉明距离</b>就是"有多像"，越小越像。</li>
 * </ol>
 *
 * <p>用缩略图而不是原图：原图是 webp（ImageIO 不支持），且解码 4000px 图只为取 9×8 太浪费；
 * 800 宽缩略图已全量生成（9443 张），既快又稳。</p>
 */
public final class MediaPhash
{
    private static final int W = 9;

    private static final int H = 8;

    private MediaPhash()
    {
    }

    /** 计算一张图的 64 位 dHash；读不了返回 null（调用方跳过该行，不写脏指纹） */
    public static Long of(Path jpg)
    {
        try
        {
            BufferedImage img = ImageIO.read(jpg.toFile());
            if (img == null)
            {
                return null;
            }
            return of(img);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    public static Long of(BufferedImage src)
    {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= 0 || sh <= 0)
        {
            return null;
        }
        double[] gray = new double[W * H];
        for (int y = 0; y < H; y++)
        {
            int y0 = (int) Math.floor((double) y * sh / H);
            int y1 = Math.max(y0 + 1, (int) Math.ceil((double) (y + 1) * sh / H));
            y1 = Math.min(y1, sh);
            for (int x = 0; x < W; x++)
            {
                int x0 = (int) Math.floor((double) x * sw / W);
                int x1 = Math.max(x0 + 1, (int) Math.ceil((double) (x + 1) * sw / W));
                x1 = Math.min(x1, sw);
                double sum = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++)
                {
                    for (int xx = x0; xx < x1; xx++)
                    {
                        int rgb = src.getRGB(xx, yy);
                        int r = (rgb >> 16) & 0xff;
                        int g = (rgb >> 8) & 0xff;
                        int b = rgb & 0xff;
                        sum += 0.299 * r + 0.587 * g + 0.114 * b;
                        n++;
                    }
                }
                gray[y * W + x] = n == 0 ? 0 : sum / n;
            }
        }
        long bits = 0L;
        for (int y = 0; y < H; y++)
        {
            for (int x = 0; x < W - 1; x++)
            {
                bits = (bits << 1) | (gray[y * W + x] < gray[y * W + x + 1] ? 1L : 0L);
            }
        }
        return Long.valueOf(bits);
    }

    /** 两个指纹的汉明距离（0..64） */
    public static int distance(long a, long b)
    {
        return Long.bitCount(a ^ b);
    }

    /** 指纹目录：thumbs/p8（缺文件返回 null，调用方决定跳过还是回退） */
    public static Path thumbOf(Path thumbsDir, String contentHash)
    {
        if (contentHash == null || contentHash.trim().isEmpty())
        {
            return null;
        }
        Path p = thumbsDir.resolve("p8").resolve(contentHash.trim().toLowerCase() + ".jpg");
        return Files.isRegularFile(p) ? p : null;
    }
}
