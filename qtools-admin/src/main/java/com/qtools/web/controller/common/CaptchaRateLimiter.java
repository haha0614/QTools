package com.qtools.web.controller.common;

import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.common.core.redis.RedisCache;
import com.qtools.common.utils.ip.IpUtils;

/**
 * 验证码接口限流。
 *
 * 为什么要它：滑块拼图每次请求都要服务端合成一张 320×160 图片，行为验证码也要写 Redis，
 * 不限流的话一个脚本就能把 CPU 和 Redis 打满（这是审计里提到的缺口）。
 *
 * 规则：同一 IP 在 60 秒窗口内，取图最多 30 次、校验最多 30 次；超出直接拒绝。
 * 计数放 Redis（多实例共享），窗口到期自动清零。
 */
@Component
public class CaptchaRateLimiter
{
    /** 取图上限（次/分钟/IP） */
    private static final int IMAGE_LIMIT = 30;
    /** 校验上限（次/分钟/IP） */
    private static final int VERIFY_LIMIT = 30;
    /** 窗口秒数 */
    private static final int WINDOW_SECONDS = 60;

    private static final String KEY_PREFIX = "captcha_rl:";

    @Autowired
    private RedisCache redisCache;

    /** 取图是否放行 */
    public boolean allowImage(HttpServletRequest request)
    {
        return allow("img", request, IMAGE_LIMIT);
    }

    /** 校验是否放行 */
    public boolean allowVerify(HttpServletRequest request)
    {
        return allow("vfy", request, VERIFY_LIMIT);
    }

    private boolean allow(String scope, HttpServletRequest request, int limit)
    {
        String ip = request == null ? "unknown" : IpUtils.getIpAddr(request);
        String key = KEY_PREFIX + scope + ":" + ip;

        Integer used = redisCache.getCacheObject(key);
        int count = used == null ? 0 : used;
        if (count >= limit)
        {
            return false;
        }
        redisCache.setCacheObject(key, count + 1, WINDOW_SECONDS, TimeUnit.SECONDS);
        return true;
    }
}
