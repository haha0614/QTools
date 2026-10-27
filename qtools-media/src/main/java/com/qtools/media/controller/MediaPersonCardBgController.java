package com.qtools.media.controller;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaPersonCardBgService;

/**
 * 人物卡背景「服务端预渲染」接口（P5.90）
 *
 * <pre>
 *   GET /api/media/person/{id}/card-bg?w=666&amp;h=204&amp;s=1&amp;dx=0&amp;dy=0&amp;blur=6&amp;d=0&amp;bg=ffffff
 * </pre>
 *
 * <h2>为什么单独一个控制器</h2>
 *
 * <p>{@link MediaPersonController} 是「只读四件套」（清单 / 详情 / 视频 / 相册），全是 JSON；
 * 这条接口返回的是<b>图片字节</b>，缓存头、ETag、错误语义都不同（前端把它当 {@code <img src>} 用，
 * 失败必须能安静回落三层方案）。放在一起会让「给人物控制器加个方法」变成「顺手改了图片缓存语义」。</p>
 *
 * <h2>参数</h2>
 *
 * <ul>
 *   <li>{@code w / h}：输出像素（缺省 666×204 = 333×102 CSS @2x DPR）；<b>1–2000 之外一律 400</b>，
 *       超过 1200×600 的按比例缩（缩不报错，因为「大屏 3x」是合法用法，只是没必要烧 CPU）；</li>
 *   <li>{@code s / dx / dy}：背景缩放与偏移（P5.88 口径）；<b>缺省用人物已保存的 bg_scale/bg_dx/bg_dy</b>，
 *       都没有就是自动几何。传了就压过库里的值 —— 普通用户的本地调整（localStorage）也能出图；</li>
 *   <li>{@code blur}：模糊半径 0–20（缺省 6），越界夹取；</li>
 *   <li>{@code d}：{@code 1} = 暗色主题（柔光往 #0b0d10 混），缺省亮色；</li>
 *   <li>{@code bg}：卡片底色 {@code RRGGBB}（拾色混合的目标色，缺省 ffffff）。</li>
 * </ul>
 *
 * <h2>缓存语义</h2>
 *
 * <p>文件名里带内容哈希（原图 size+mtime、几何参数、模糊、主题、尺寸都进哈希），
 * 所以「同一个 URL 的字节永不变化」→ {@code immutable} 一年。前端换头像 / 改调整 → URL 里的
 * {@code v}（头像版本）与参数变化 → 新文件；旧文件 30 天没人访问自动清掉。</p>
 *
 * <p>排障头：{@code X-Card-Bg: HIT|MISS}、{@code X-Card-Bg-Key}（内容哈希）、{@code X-Card-Bg-Ms}（本次耗时）。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaPersonCardBgController
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonCardBgController.class);

    /** 内容寻址 → 浏览器一年不回源（与 MediaFileController.IMMUTABLE_CACHE 同值） */
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";

    /** 尺寸上限：与 IMediaPersonCardBgService 的实现常量一致（这里只做入参校验，缩放交给服务层） */
    private static final int DIM_MAX = 2000;

    @Autowired
    private IMediaPersonCardBgService mediaPersonCardBgService;

    @GetMapping("/person/{id}/card-bg")
    public ResponseEntity<byte[]> cardBg(@PathVariable("id") Long id,
            @RequestParam(value = "w", required = false) String w,
            @RequestParam(value = "h", required = false) String h,
            @RequestParam(value = "s", required = false) String s,
            @RequestParam(value = "dx", required = false) String dx,
            @RequestParam(value = "dy", required = false) String dy,
            @RequestParam(value = "blur", required = false) String blur,
            @RequestParam(value = "d", required = false) String d,
            @RequestParam(value = "bg", required = false) String bg)
    {
        Integer width = dim(w, "w");
        Integer height = dim(h, "h");
        Integer blurPx = intParam(blur, "blur");
        BigDecimal scale = decimalParam(s, "s");
        BigDecimal offsetX = decimalParam(dx, "dx");
        BigDecimal offsetY = decimalParam(dy, "dy");
        boolean dark = "1".equals(trim(d)) || "true".equalsIgnoreCase(trim(d)) || "dark".equalsIgnoreCase(trim(d));

        IMediaPersonCardBgService.CardBgImage image = mediaPersonCardBgService.cardBg(id, width, height, scale,
                offsetX, offsetY, blurPx, dark, bg);
        log.debug("人物卡背景 id={} {}x{} hit={} key={} cost={}ms", id, image.getWidth(), image.getHeight(),
                image.isHit(), image.getKey(), image.getRenderMs());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE_CACHE)
                .header("ETag", "\"" + image.getKey() + "\"")
                .header("X-Card-Bg", image.isHit() ? "HIT" : "MISS")
                .header("X-Card-Bg-Key", image.getKey())
                .header("X-Card-Bg-Ms", String.valueOf(image.getRenderMs()))
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(image.getBytes().length)
                .body(image.getBytes());
    }

    /** 尺寸：缺省（null）由服务层补；给了就必须是 1–2000 的整数，否则 400（0 / 负数 / 超大都不静默夹取） */
    private static Integer dim(String raw, String name)
    {
        String text = trim(raw);
        if (text.isEmpty())
        {
            return null;
        }
        int value;
        try
        {
            value = Integer.parseInt(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest(name + " 必须是整数: " + raw);
        }
        if (value < 1 || value > DIM_MAX)
        {
            throw MediaException.badRequest(name + " 越界：应在 1–" + DIM_MAX + "（收到 " + value + "）");
        }
        return value;
    }

    private static Integer intParam(String raw, String name)
    {
        String text = trim(raw);
        if (text.isEmpty())
        {
            return null;
        }
        try
        {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest(name + " 必须是整数: " + raw);
        }
    }

    private static BigDecimal decimalParam(String raw, String name)
    {
        String text = trim(raw);
        if (text.isEmpty())
        {
            return null;
        }
        try
        {
            return new BigDecimal(text);
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest(name + " 必须是数字: " + raw);
        }
    }

    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }
}
