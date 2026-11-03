package com.qtools.web.controller.common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.core.redis.RedisCache;
import com.qtools.common.utils.uuid.IdUtils;
import com.qtools.system.service.ISysConfigService;

/**
 * 行为验证码：按住滑块拖到最右端，提交拖动轨迹由服务端判定是不是"人手"。
 *
 * 判定项（都在服务端做，前端只是采集）：
 *   1. 总时长在 350ms ~ 4s 之间（太快=脚本，太慢=挂机）；
 *   2. 采样点数 >= 8（浏览器每帧至少一个点）；
 *   3. 相邻两点位移 <= 180px（防瞬移）；
 *   4. 终点必须落在轨道末端附近（与滑动条宽度比较）。
 *
 * 通过后同样发放一次性 permit，与滑块拼图共用 `slider_ok:<uuid>` 的校验路径。
 */
@RestController
public class BehaviorCaptchaController
{
    private static final String KEY_PERMIT = "slider_ok:";
    private static final int EXPIRE_MINUTES = 2;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private CaptchaRateLimiter rateLimiter;

    @GetMapping("/captchaBehavior")
    public AjaxResult create(jakarta.servlet.http.HttpServletRequest request)
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
        data.put("uuid", IdUtils.simpleUUID());
        data.put("text", "按住滑块，拖到最右端");
        return AjaxResult.success(data);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/captchaBehaviorVerify")
    public AjaxResult verify(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request)
    {
        if (!rateLimiter.allowVerify(request))
        {
            return AjaxResult.error("请求过于频繁，请稍后再试");
        }
        String uuid = body.get("uuid") == null ? "" : String.valueOf(body.get("uuid"));
        if (uuid.isEmpty())
        {
            return AjaxResult.error("验证已过期，请重试");
        }

        Object rawSamples = body.get("samples");
        if (!(rawSamples instanceof List))
        {
            return AjaxResult.error("轨迹无效，请重试");
        }
        List<Map<String, Object>> samples = (List<Map<String, Object>>) rawSamples;
        if (samples.size() < 8)
        {
            return AjaxResult.error("轨迹过短，请正常拖动");
        }

        double duration = toDouble(body.get("durationMs"), 0);
        if (duration < 350 || duration > 4000)
        {
            return AjaxResult.error("拖动节奏异常，请重试");
        }

        double trackWidth = toDouble(body.get("trackWidth"), 0);
        double endX = toDouble(body.get("endX"), -1);
        if (trackWidth <= 0 || endX < 0 || endX < trackWidth - 8)
        {
            return AjaxResult.error("没有拖到最右端");
        }

        double lastX = -1;
        for (Map<String, Object> s : samples)
        {
            double x = toDouble(s.get("x"), -1);
            if (x < 0)
            {
                return AjaxResult.error("轨迹无效，请重试");
            }
            if (lastX >= 0 && Math.abs(x - lastX) > 180)
            {
                return AjaxResult.error("轨迹异常，请重试");
            }
            lastX = x;
        }

        String permit = IdUtils.simpleUUID();
        redisCache.setCacheObject(KEY_PERMIT + uuid, permit, EXPIRE_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> data = new HashMap<>();
        data.put("permit", permit);
        return AjaxResult.success(data);
    }

    private double toDouble(Object v, double fallback)
    {
        if (v == null)
        {
            return fallback;
        }
        try
        {
            return Double.parseDouble(String.valueOf(v));
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }
}
