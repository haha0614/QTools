package com.qtools.web.controller.common;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.FastByteArrayOutputStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.constant.CacheConstants;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.core.redis.RedisCache;
import com.qtools.common.utils.sign.Base64;
import com.qtools.common.utils.uuid.IdUtils;
import com.qtools.system.service.ISysConfigService;

/**
 * 滑块拼图验证码。
 *
 * 与原来的字符/算术验证码相比：用户只需要把拼图拖到缺口，不用辨认扭曲字母。
 * 图片完全由服务端合成（不依赖任何外部素材），答案只存在 Redis，一次性、2 分钟过期。
 *
 * 流程：
 *   1. GET  /captchaSlider         -> { uuid, bg, piece, y, size, width, height }
 *   2. POST /captchaSliderVerify   -> { ok, permit }   偏移量在容差内则发放一次性凭证
 *   3. 登录时把 permit 当作 code 提交，SysLoginService 校验 slider_ok:<uuid>
 */
@RestController
public class SliderCaptchaController
{
    private static final int WIDTH = 320;
    private static final int HEIGHT = 160;
    private static final int PIECE = 48;
    /** 容差（像素）：手指/鼠标拖动很难精确对齐，给一点余量 */
    private static final int TOLERANCE = 6;
    /** 有效期（分钟） */
    private static final int EXPIRE_MINUTES = 2;

    private static final String KEY_ANSWER = "slider_codes:";
    private static final String KEY_PERMIT = "slider_ok:";

    private final Random random = new Random();

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private CaptchaRateLimiter rateLimiter;

    @GetMapping("/captchaSlider")
    public AjaxResult create(jakarta.servlet.http.HttpServletRequest request) throws IOException
    {
        if (!rateLimiter.allowImage(request))
        {
            return AjaxResult.error("请求过于频繁，请稍后再试");
        }
        Map<String, Object> data = new HashMap<>();
        boolean enabled = configService.selectCaptchaEnabled();
        data.put("captchaEnabled", enabled);
        if (!enabled)
        {
            return AjaxResult.success(data);
        }

        int pieceSize = PIECE;
        int y = 12 + random.nextInt(HEIGHT - pieceSize - 24);
        int x = 90 + random.nextInt(WIDTH - pieceSize - 110);

        BufferedImage bg = drawBackground();
        BufferedImage piece = new BufferedImage(pieceSize, pieceSize, BufferedImage.TYPE_INT_ARGB);

        // 拼图块：把背景对应区域按拼图形状裁出来
        Graphics2D pg = piece.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        pg.setClip(puzzleShape(0, 0, pieceSize));
        pg.drawImage(bg, -x, -y, null);
        pg.setClip(null);
        pg.setColor(new Color(255, 255, 255, 90));
        pg.setStroke(new BasicStroke(1.6f));
        pg.draw(puzzleShape(0, 0, pieceSize));
        pg.dispose();

        // 背景上挖出缺口（半透明深色 + 描边）
        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape hole = puzzleShape(x, y, pieceSize);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.setColor(new Color(12, 16, 24));
        g.fill(hole);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(255, 255, 255, 150));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(hole);
        g.dispose();

        String uuid = IdUtils.simpleUUID();
        redisCache.setCacheObject(KEY_ANSWER + uuid, x, EXPIRE_MINUTES, TimeUnit.MINUTES);

        data.put("uuid", uuid);
        data.put("bg", toBase64(bg));
        data.put("piece", toBase64(piece));
        data.put("y", y);
        data.put("size", pieceSize);
        data.put("width", WIDTH);
        data.put("height", HEIGHT);
        return AjaxResult.success(data);
    }

    @PostMapping("/captchaSliderVerify")
    public AjaxResult verify(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request)
    {
        if (!rateLimiter.allowVerify(request))
        {
            return AjaxResult.error("请求过于频繁，请稍后再试");
        }
        String uuid = body.get("uuid") == null ? "" : String.valueOf(body.get("uuid"));
        int offset = body.get("x") == null ? -9999 : (int) Double.parseDouble(String.valueOf(body.get("x")));

        String answerKey = KEY_ANSWER + uuid;
        Object cached = redisCache.getCacheObject(answerKey);
        if (cached == null)
        {
            return AjaxResult.error("验证已过期，请重新拖动");
        }
        int answer = (int) Double.parseDouble(String.valueOf(cached));
        // 一次性：无论成功失败都作废，避免暴力试
        redisCache.deleteObject(answerKey);

        if (Math.abs(offset - answer) > TOLERANCE)
        {
            return AjaxResult.error("位置不对，再来一次");
        }

        String permit = IdUtils.simpleUUID();
        redisCache.setCacheObject(KEY_PERMIT + uuid, permit, EXPIRE_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> data = new HashMap<>();
        data.put("permit", permit);
        return AjaxResult.success(data);
    }

    /** 拼图块形状：圆角方块 + 右侧凸起 + 上方凹槽，靠 Path2D 拼出来 */
    private Shape puzzleShape(double x, double y, double size)
    {
        Path2D.Double path = new Path2D.Double();
        double knob = size * 0.22;
        double cx = x + size - knob;
        double cy = y + size * 0.5;
        path.append(new RoundRectangle2D.Double(x, y, size, size, size * 0.28, size * 0.28), false);
        path.append(new Ellipse2D.Double(cx - knob * 0.55, cy - knob, knob * 1.6, knob * 2), false);
        Path2D.Double top = new Path2D.Double();
        top.append(new Ellipse2D.Double(x + size * 0.5 - knob * 0.8, y - knob * 0.75, knob * 1.6, knob * 1.6), false);
        path.append(top, false);
        return path;
    }

    /** 合成一张底色渐变 + 随机色块 + 噪点的背景，避免依赖外部图片素材 */
    private BufferedImage drawBackground()
    {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int hue = random.nextInt(360);
        Color from = Color.getHSBColor(hue / 360f, 0.62f, 0.86f);
        Color to = Color.getHSBColor(((hue + 48) % 360) / 360f, 0.72f, 0.52f);
        g.setPaint(new GradientPaint(0, 0, from, WIDTH, HEIGHT, to));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        for (int i = 0; i < 14; i++)
        {
            g.setColor(new Color(random.nextInt(255), random.nextInt(255), random.nextInt(255), 18 + random.nextInt(26)));
            int w = 20 + random.nextInt(70);
            int h = 20 + random.nextInt(70);
            g.fillOval(random.nextInt(WIDTH) - w / 2, random.nextInt(HEIGHT) - h / 2, w, h);
        }
        for (int i = 0; i < 240; i++)
        {
            g.setColor(new Color(255, 255, 255, 20 + random.nextInt(40)));
            g.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
        }
        g.dispose();
        return img;
    }

    private String toBase64(BufferedImage image) throws IOException
    {
        FastByteArrayOutputStream os = new FastByteArrayOutputStream();
        ImageIO.write(image, "png", os);
        return Base64.encode(os.toByteArray());
    }
}
