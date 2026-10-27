package com.qtools.media.support;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 人物卡背景「服务端预渲染」的纯算法实现（P5.90）
 *
 * <h2>它到底在算什么</h2>
 *
 * <p>P5.84–P5.88 的背景是同<b>一张原图三层</b> + 拾色底 + 柔光，几何由前端
 * {@code personBgImage.layoutCrop} + {@code personBgAdjust.applyAdjust} 现算，写进 CSS 变量；
 * 每张卡每次加载都要跑一遍 measure → layout → 写变量 → 三层 filter/mask 合成。
 * 用户口径：「把我所选的区域裁下来作为一个文件或缓存，然后每次加载就直接加载这个图片就行了」。</p>
 *
 * <p>本类把那条链路<b>逐像素复刻</b>成一张 JPEG，公式与常量与前端一一对应（改动必须两边同步）：</p>
 * <pre>
 *   imgW = bgW × 0.44 / cropW          （personBgImage.MAGNIFY / layoutCrop）
 *   imgH = imgW × natH / natW
 *   tx   = bgW × 0.76 − imgW × cx      （TARGET_X / cropCenter）
 *   ty   = bgH × 0.50 − imgH × cy
 *   imgW *= scale; imgH *= scale; tx += dx × bgW; ty += dy × bgH   （applyAdjust，dx/dy 是卡片宽高比例）
 * </pre>
 *
 * <h2>合成顺序（自下而上，与 DOM 完全一致）</h2>
 * <ol>
 *   <li>卡片底色 {@code --pw-tone}：原图 8×8 降采样均值 → 按主题亮度约束二分混到 {@code --bg-2}（personTone.toneFill）；</li>
 *   <li>{@code .pw-glow}：145% × 165% @ (0%,50%) 的径向光晕，26% 实心、70% 透明，整体 opacity .35；</li>
 *   <li>清晰层：原图按几何摆放，mask = {@code --pw-img-fade}（图片自身左缘 0% → .45 @9% → 1 @20%）；</li>
 *   <li>模糊层：同一张图 blur(半径) + saturate(1.1) + brightness(.9)，
 *       mask = intersect(横向停靠点 0/.18/.34/.50/.64/.78 → 1/.92/.62/.30/.08/0，图片左缘渐显)；</li>
 *   <li>雾层：同一张图 blur(半径 × 2.6)，mask = intersect(横向 0% 实 → 52% 透明，图片左缘渐显)。</li>
 * </ol>
 *
 * <p><b>mask 坐标系</b>：CSS 的 {@code mask-size: 100% 100%} 是相对<b>图片元素盒子</b>（imgW × imgH），
 * 不是卡片；所以横向停靠点的 u = (x − tx) / imgW。这一点写错就会「右边也糊」，是复刻里最容易踩的坑。</p>
 *
 * <p><b>为什么不用 {@code ConvolveOp}</b>：它对 TYPE_INT_ARGB 走非预乘通道，图片边缘会渗黑边；
 * CSS {@code blur()} 是 std-dev = 半径 的高斯，且元素外按透明处理。这里用预乘浮点 + 可分离高斯，
 * 并且在大半径时先降采样再模糊（视觉等价、首个请求的耗时从 ~300ms 降到 ~60ms）。</p>
 *
 * <p>本类无状态、无 Spring 依赖，便于单测与「同一份代码跑在服务端」的对照实验。</p>
 *
 * @author qtools
 */
public final class MediaPersonCardBg
{
    /** 裁剪块在卡片上占的宽度比例（personBgImage.MAGNIFY） */
    public static final double MAGNIFY = 0.44;

    /** 裁剪中心钉在卡片的 76% / 50%（personBgImage.TARGET_X / TARGET_Y） */
    public static final double TARGET_X = 0.76;

    public static final double TARGET_Y = 0.5;

    /** 模糊层横向渐隐终点（卡片 --pw-blur-fade-end 的默认值） */
    public static final double FADE_END = 0.78;

    /** 雾层横向渐隐终点（.pw-bg-fog 的 mask 到 52% 透明） */
    public static final double FOG_END = 0.52;

    /** 雾层模糊倍率（.pw-bg-fog 的 calc(半径 × 2.6)） */
    public static final double FOG_FACTOR = 2.6;

    /** 模糊层调色（.pw-bg-blur 的 saturate / brightness） */
    public static final double BLUR_SATURATE = 1.1;

    public static final double BLUR_BRIGHTNESS = 0.9;

    /** 柔光层整体不透明度（.pw-glow 的 opacity） */
    public static final double GLOW_OPACITY = 0.35;

    /** 拾色约束（personTone.LIGHT_MIN_LUM / DARK_MAX_LUM） */
    public static final double LIGHT_MIN_LUM = 0.52;

    public static final double DARK_MAX_LUM = 0.045;

    /** 图片左缘渐显停靠点：位置, alpha（--pw-img-fade） */
    private static final double[] IMG_FADE = { 0.0, 0.0, 0.09, 0.45, 0.20, 1.0 };

    /** 模糊层横向停靠点（.pw-bg-blur 的 mask 第一层） */
    private static final double[] BLUR_STOPS = { 0.0, 1.0, 0.18, 0.92, 0.34, 0.62, 0.50, 0.30, 0.64, 0.08,
            FADE_END, 0.0 };

    /** 雾层横向停靠点（.pw-bg-fog 的 mask 第一层） */
    private static final double[] FOG_STOPS = { 0.0, 1.0, FOG_END, 0.0 };

    private MediaPersonCardBg()
    {
    }

    /** 几何结果（单位：卡片像素；tx / ty 以 .pw-bg 左上角为原点） */
    public static final class Geometry
    {
        public final double imgW;

        public final double imgH;

        public final double tx;

        public final double ty;

        public final double cropX;

        public final double cropY;

        Geometry(double imgW, double imgH, double tx, double ty, double cropX, double cropY)
        {
            this.imgW = imgW;
            this.imgH = imgH;
            this.tx = tx;
            this.ty = ty;
            this.cropX = cropX;
            this.cropY = cropY;
        }
    }

    /**
     * 与前端 layoutCrop + applyAdjust 逐式一致的几何
     *
     * @param cropX 裁剪中心相对整图的 x（0..1）
     * @param cropW 裁剪块宽度占比（0..1，&gt;0）
     */
    public static Geometry geometry(int natW, int natH, int bgW, int bgH, double cropX, double cropY,
            double cropW, double cropH, double scale, double dx, double dy)
    {
        if (natW <= 0 || natH <= 0 || bgW <= 0 || bgH <= 0)
        {
            return null;
        }
        double cw = cropW > 0 ? cropW : 1.0;
        double ch = cropH > 0 ? cropH : 1.0;
        double cx = clamp(cropX + cw / 2.0, 0, 1);
        double cy = clamp(cropY + ch / 2.0, 0, 1);
        double baseW = bgW * MAGNIFY / cw;
        double imgW = baseW * scale;
        double imgH = baseW * natH / natW * scale;
        double tx = bgW * TARGET_X - imgW * cx + dx * bgW;
        double ty = bgH * TARGET_Y - imgH * cy + dy * bgH;
        return new Geometry(imgW, imgH, tx, ty, cx, cy);
    }

    /**
     * 渲染最终的卡片背景
     *
     * @param src      头像原图（未裁剪的整图；裁剪只用于几何）
     * @param g        几何（{@link #geometry}）
     * @param w        输出宽（= 卡片 .pw-bg 的像素宽，调用方已夹到上限）
     * @param h        输出高
     * @param blurPx   模糊半径（滑块值，0–20；0 = 清晰版）
     * @param themeBg  卡片底色 --bg-2（拾色混合的目标色）
     * @param dark     是否暗色主题（只影响柔光的混合目标色）
     */
    public static BufferedImage render(BufferedImage src, Geometry g, int w, int h, double blurPx, int[] themeBg,
            boolean dark)
    {
        int[] tone = toneFill(sampleTone(src), themeBg, dark);
        int[] fill = tone != null ? tone : themeBg;

        float[] photo = drawPhoto(src, g, w, h);
        float[] blurLayer = blurPx > 0.05 ? gaussianBlur(photo, w, h, blurPx) : null;
        float[] fogLayer = blurPx > 0.05 ? gaussianBlur(photo, w, h, blurPx * FOG_FACTOR) : null;
        if (blurLayer != null)
        {
            saturateBrighten(blurLayer, BLUR_SATURATE, BLUR_BRIGHTNESS);
        }

        double[] maskSharp = new double[w];
        double[] maskBlur = new double[w];
        double[] maskFog = new double[w];
        for (int x = 0; x < w; x++)
        {
            double u = (x + 0.5 - g.tx) / g.imgW;
            double fade = u < 0 || u > 1 ? 0 : stops(IMG_FADE, u);
            maskSharp[x] = fade;
            maskBlur[x] = Math.min(stops(BLUR_STOPS, u), fade);
            maskFog[x] = Math.min(stops(FOG_STOPS, u), fade);
        }

        int[] glow = mix(fill, dark ? new int[] { 11, 13, 16 } : new int[] { 255, 255, 255 }, 0.7);
        double rx = 1.45 * w;
        double ry = 1.65 * h;
        double gcy = 0.5 * h;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                double r = fill[0];
                double gr = fill[1];
                double b = fill[2];

                double t = Math.sqrt(sq((x + 0.5) / rx) + sq((y + 0.5 - gcy) / ry));
                double ga = GLOW_OPACITY * (t <= 0.26 ? 1 : (t >= 0.70 ? 0 : (0.70 - t) / 0.44));
                if (ga > 0)
                {
                    r = glow[0] * ga + r * (1 - ga);
                    gr = glow[1] * ga + gr * (1 - ga);
                    b = glow[2] * ga + b * (1 - ga);
                }

                int idx = (y * w + x) * 4;
                double[] c = { r, gr, b };
                c = over(c, photo, idx, maskSharp[x]);
                if (blurLayer != null)
                {
                    c = over(c, blurLayer, idx, maskBlur[x]);
                    c = over(c, fogLayer, idx, maskFog[x]);
                }
                out.setRGB(x, y, (clamp255(c[0]) << 16) | (clamp255(c[1]) << 8) | clamp255(c[2]));
            }
        }
        return out;
    }

    /** jpeg 编码（默认 82% 质量；与 ImageIO 默认的 0.75 相比边框和皮肤明显干净） */
    public static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException
    {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(raw))
        {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed())
            {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        }
        finally
        {
            writer.dispose();
        }
        return raw.toByteArray();
    }

    /** 8×8 降采样的均值 RGB（personTone.sampleTone 的服务端镜像；透明像素按 alpha &gt; 8 过滤） */
    public static int[] sampleTone(BufferedImage src)
    {
        if (src == null)
        {
            return null;
        }
        BufferedImage small = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, 8, 8, null);
        }
        finally
        {
            g.dispose();
        }
        long r = 0;
        long gr = 0;
        long b = 0;
        long count = 0;
        for (int y = 0; y < 8; y++)
        {
            for (int x = 0; x < 8; x++)
            {
                int px = small.getRGB(x, y);
                if (((px >>> 24) & 0xff) < 8)
                {
                    continue;
                }
                r += (px >> 16) & 0xff;
                gr += (px >> 8) & 0xff;
                b += px & 0xff;
                count++;
            }
        }
        if (count == 0)
        {
            return null;
        }
        return new int[] { (int) Math.round((double) r / count), (int) Math.round((double) gr / count),
                (int) Math.round((double) b / count) };
    }

    /**
     * 拾色落地色（personTone.toneFill 的镜像）：在亮度约束内最大化采样色占比（二分 18 次）
     *
     * @return 落地色；约束下界本身就不满足（极少见）返回 null，调用方退回卡片底色
     */
    public static int[] toneFill(int[] tone, int[] themeBg, boolean dark)
    {
        if (tone == null || themeBg == null)
        {
            return null;
        }
        boolean light = !dark;
        if (!okLum(luminance(themeBg), light))
        {
            return null;
        }
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 18; i++)
        {
            double mid = (lo + hi) / 2;
            if (okLum(luminance(mix(themeBg, tone, mid)), light))
            {
                lo = mid;
            }
            else
            {
                hi = mid;
            }
        }
        return mix(themeBg, tone, lo);
    }

    /** WCAG 相对亮度 */
    public static double luminance(int[] rgb)
    {
        double f0 = channel(rgb[0]);
        double f1 = channel(rgb[1]);
        double f2 = channel(rgb[2]);
        return 0.2126 * f0 + 0.7152 * f1 + 0.0722 * f2;
    }

    private static double channel(int v)
    {
        double s = clamp255(v) / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static boolean okLum(double lum, boolean light)
    {
        return light ? lum >= LIGHT_MIN_LUM : lum <= DARK_MAX_LUM;
    }

    /** 线性混合：t = 采样色占比（0 = 全底色，1 = 全采样色） */
    public static int[] mix(int[] bg, int[] tone, double t)
    {
        double k = clamp(t, 0, 1);
        return new int[] { clamp255(bg[0] * (1 - k) + tone[0] * k), clamp255(bg[1] * (1 - k) + tone[1] * k),
                clamp255(bg[2] * (1 - k) + tone[2] * k) };
    }

    /* ------------------------------------------------------------------ 绘制 */

    /** 把原图按几何画到 w×h 的预乘浮点画布上（元素外为透明） */
    private static float[] drawPhoto(BufferedImage src, Geometry g, int w, int h)
    {
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = layer.createGraphics();
        try
        {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(src, (int) Math.round(g.tx), (int) Math.round(g.ty), (int) Math.round(g.imgW),
                    (int) Math.round(g.imgH), null);
        }
        finally
        {
            g2.dispose();
        }
        float[] out = new float[w * h * 4];
        int[] argb = new int[w * h];
        layer.getRGB(0, 0, w, h, argb, 0, w);
        for (int i = 0; i < argb.length; i++)
        {
            int px = argb[i];
            float a = ((px >>> 24) & 0xff) / 255f;
            out[i * 4] = ((px >> 16) & 0xff) * a;
            out[i * 4 + 1] = ((px >> 8) & 0xff) * a;
            out[i * 4 + 2] = (px & 0xff) * a;
            out[i * 4 + 3] = a;
        }
        return out;
    }

    /* ------------------------------------------------------------------ 模糊 */

    /**
     * 预乘高斯模糊
     *
     * <p>半径 &gt; 6 时先按整数因子降采样再模糊（σ 同比缩小），最后双线性放大回原尺寸：
     * 高斯是低通，降采样损失的只是本来就看不见的高频，实测与「全程原尺寸」的差异在 1–2 个色阶内。</p>
     */
    public static float[] gaussianBlur(float[] src, int w, int h, double sigma)
    {
        if (sigma < 0.05 || w <= 0 || h <= 0)
        {
            return src;
        }
        int factor = sigma > 6 ? Math.max(1, (int) Math.round(sigma / 4.0)) : 1;
        if (factor <= 1)
        {
            return gaussianBlurExact(src, w, h, sigma);
        }
        int sw = Math.max(1, w / factor);
        int sh = Math.max(1, h / factor);
        float[] small = boxDown(src, w, h, sw, sh, factor);
        float[] blurred = gaussianBlurExact(small, sw, sh, sigma / factor);
        return bilinearUp(blurred, sw, sh, w, h);
    }

    private static float[] gaussianBlurExact(float[] src, int w, int h, double sigma)
    {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        double[] kernel = new double[radius * 2 + 1];
        double sum = 0;
        for (int k = -radius; k <= radius; k++)
        {
            double v = Math.exp(-(k * (double) k) / (2 * sigma * sigma));
            kernel[k + radius] = v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++)
        {
            kernel[i] /= sum;
        }
        float[] tmp = new float[src.length];
        // 横向
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                double a0 = 0, a1 = 0, a2 = 0, a3 = 0;
                for (int k = -radius; k <= radius; k++)
                {
                    int xx = x + k;
                    if (xx < 0 || xx >= w)
                    {
                        continue;
                    }
                    double wgt = kernel[k + radius];
                    int i = (y * w + xx) * 4;
                    a0 += src[i] * wgt;
                    a1 += src[i + 1] * wgt;
                    a2 += src[i + 2] * wgt;
                    a3 += src[i + 3] * wgt;
                }
                int o = (y * w + x) * 4;
                tmp[o] = (float) a0;
                tmp[o + 1] = (float) a1;
                tmp[o + 2] = (float) a2;
                tmp[o + 3] = (float) a3;
            }
        }
        float[] out = new float[src.length];
        // 纵向
        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                double a0 = 0, a1 = 0, a2 = 0, a3 = 0;
                for (int k = -radius; k <= radius; k++)
                {
                    int yy = y + k;
                    if (yy < 0 || yy >= h)
                    {
                        continue;
                    }
                    double wgt = kernel[k + radius];
                    int i = (yy * w + x) * 4;
                    a0 += tmp[i] * wgt;
                    a1 += tmp[i + 1] * wgt;
                    a2 += tmp[i + 2] * wgt;
                    a3 += tmp[i + 3] * wgt;
                }
                int o = (y * w + x) * 4;
                out[o] = (float) a0;
                out[o + 1] = (float) a1;
                out[o + 2] = (float) a2;
                out[o + 3] = (float) a3;
            }
        }
        return out;
    }

    private static float[] boxDown(float[] src, int w, int h, int sw, int sh, int factor)
    {
        float[] out = new float[sw * sh * 4];
        for (int y = 0; y < sh; y++)
        {
            for (int x = 0; x < sw; x++)
            {
                double a0 = 0, a1 = 0, a2 = 0, a3 = 0;
                int n = 0;
                for (int dy = 0; dy < factor; dy++)
                {
                    int yy = y * factor + dy;
                    if (yy >= h)
                    {
                        break;
                    }
                    for (int dx = 0; dx < factor; dx++)
                    {
                        int xx = x * factor + dx;
                        if (xx >= w)
                        {
                            break;
                        }
                        int i = (yy * w + xx) * 4;
                        a0 += src[i];
                        a1 += src[i + 1];
                        a2 += src[i + 2];
                        a3 += src[i + 3];
                        n++;
                    }
                }
                int o = (y * sw + x) * 4;
                out[o] = (float) (a0 / Math.max(1, n));
                out[o + 1] = (float) (a1 / Math.max(1, n));
                out[o + 2] = (float) (a2 / Math.max(1, n));
                out[o + 3] = (float) (a3 / Math.max(1, n));
            }
        }
        return out;
    }

    private static float[] bilinearUp(float[] src, int sw, int sh, int w, int h)
    {
        float[] out = new float[w * h * 4];
        double sx = (double) sw / w;
        double sy = (double) sh / h;
        for (int y = 0; y < h; y++)
        {
            double fy = (y + 0.5) * sy - 0.5;
            int y0 = (int) Math.floor(fy);
            double ty = fy - y0;
            int y1 = y0 + 1;
            if (y0 < 0)
            {
                y0 = 0;
                ty = 0;
            }
            if (y1 >= sh)
            {
                y1 = sh - 1;
                ty = 0;
            }
            for (int x = 0; x < w; x++)
            {
                double fx = (x + 0.5) * sx - 0.5;
                int x0 = (int) Math.floor(fx);
                double tx = fx - x0;
                int x1 = x0 + 1;
                if (x0 < 0)
                {
                    x0 = 0;
                    tx = 0;
                }
                if (x1 >= sw)
                {
                    x1 = sw - 1;
                    tx = 0;
                }
                int i00 = (y0 * sw + x0) * 4;
                int i01 = (y0 * sw + x1) * 4;
                int i10 = (y1 * sw + x0) * 4;
                int i11 = (y1 * sw + x1) * 4;
                int o = (y * w + x) * 4;
                for (int c = 0; c < 4; c++)
                {
                    double top = src[i00 + c] * (1 - tx) + src[i01 + c] * tx;
                    double bot = src[i10 + c] * (1 - tx) + src[i11 + c] * tx;
                    out[o + c] = (float) (top * (1 - ty) + bot * ty);
                }
            }
        }
        return out;
    }

    /** CSS filter: saturate(s) brightness(b) —— 在预乘通道上换算回直通色再算，避免透明边缘发灰 */
    private static void saturateBrighten(float[] layer, double saturate, double brightness)
    {
        for (int i = 0; i < layer.length; i += 4)
        {
            float a = layer[i + 3];
            if (a <= 0.0001f)
            {
                continue;
            }
            double r = layer[i] / a;
            double g = layer[i + 1] / a;
            double b = layer[i + 2] / a;
            double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            r = (luma + (r - luma) * saturate) * brightness;
            g = (luma + (g - luma) * saturate) * brightness;
            b = (luma + (b - luma) * saturate) * brightness;
            layer[i] = (float) (clamp(r, 0, 255) * a);
            layer[i + 1] = (float) (clamp(g, 0, 255) * a);
            layer[i + 2] = (float) (clamp(b, 0, 255) * a);
        }
    }

    /* ------------------------------------------------------------------ 合成 */

    private static double[] over(double[] dst, float[] layer, int idx, double mask)
    {
        double a = layer[idx + 3] * mask;
        if (a <= 0.0001)
        {
            return dst;
        }
        // layer 的三个通道是「已按自身 alpha 预乘」的值，mask 是额外的不透明度，
        // 必须同乘到颜色上（只乘 alpha 会让 55% 的层贡献 100% 的颜色 —— 整张图会发白过曝）
        double ia = 1 - a;
        double r = layer[idx] * mask + dst[0] * ia;
        double g = layer[idx + 1] * mask + dst[1] * ia;
        double b = layer[idx + 2] * mask + dst[2] * ia;
        return new double[] { r, g, b };
    }

    /* ------------------------------------------------------------------ 工具 */

    /** 多停靠点 alpha 表（成对：位置, alpha；按位置升序） */
    private static double stops(double[] table, double t)
    {
        if (t <= table[0])
        {
            return table[1];
        }
        for (int i = 2; i < table.length; i += 2)
        {
            if (t <= table[i])
            {
                double x0 = table[i - 2];
                double a0 = table[i - 1];
                double span = table[i] - x0;
                double k = span <= 0 ? 0 : (t - x0) / span;
                return a0 + (table[i + 1] - a0) * k;
            }
        }
        return table[table.length - 1];
    }

    private static double sq(double v)
    {
        return v * v;
    }

    private static double clamp(double v, double lo, double hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp255(double v)
    {
        long r = Math.round(v);
        return r < 0 ? 0 : (r > 255 ? 255 : (int) r);
    }

    /** 只给日志 / 缓存键用：几何的稳定字符串（3 位小数） */
    public static String geometryKey(Geometry g)
    {
        if (g == null)
        {
            return "none";
        }
        return String.format(Locale.ROOT, "%.3f/%.3f/%.3f/%.3f", g.imgW, g.imgH, g.tx, g.ty);
    }
}
