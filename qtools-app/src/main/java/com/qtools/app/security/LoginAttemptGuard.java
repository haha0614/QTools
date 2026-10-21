package com.qtools.app.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.app.config.AppProperties;

/**
 * 登录失败限流（同 IP + 标识，窗口内计数）
 *
 * <p>计数放在进程内：本站是单实例部署（8080 一个 JVM），够用且不引入额外依赖。
 * 若将来多实例，把这里换成 Redis 计数器即可，接口不变。</p>
 *
 * @author qtools
 */
@Component
public class LoginAttemptGuard
{
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptGuard.class);

    /** 窗口内的失败计数 */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Autowired
    private AppProperties properties;

    /** 一次窗口内的计数：首次失败时间 + 失败次数 */
    private static class Counter
    {
        private final long windowStart;

        private final AtomicInteger failures = new AtomicInteger();

        Counter(long windowStart)
        {
            this.windowStart = windowStart;
        }
    }

    /** 限流键：IP + 标识（统一小写，避免大小写绕过） */
    public static String key(String ip, String identifier)
    {
        String safeIp = ip == null ? "" : ip.trim();
        String safeIdentifier = identifier == null ? "" : identifier.trim().toLowerCase();
        return safeIp + '|' + safeIdentifier;
    }

    /** 是否已达上限（超过即拒绝，不再校验密码） */
    public boolean isBlocked(String key)
    {
        Counter counter = counters.get(key);
        if (counter == null)
        {
            return false;
        }
        if (expired(counter))
        {
            counters.remove(key, counter);
            return false;
        }
        return counter.failures.get() >= properties.getSecurity().getLoginMaxAttempts();
    }

    /** 记一次失败 */
    public void recordFailure(String key)
    {
        long now = System.currentTimeMillis();
        Counter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > windowMillis())
            {
                Counter fresh = new Counter(now);
                fresh.failures.incrementAndGet();
                return fresh;
            }
            existing.failures.incrementAndGet();
            return existing;
        });
        // 计数表不该无限长：窗口已过期的条目顺手清掉（O(n)，n 只是活跃登录失败的键数）
        if (counters.size() > 1024)
        {
            counters.entrySet().removeIf(entry -> expired(entry.getValue()));
        }
        if (counter.failures.get() == properties.getSecurity().getLoginMaxAttempts())
        {
            // 只提示一次，避免把刷接口的日志灌满
            log.info("用户端登录失败次数达到上限 key={}（窗口 {} 分钟）", key,
                    properties.getSecurity().getLoginWindowMinutes());
        }
    }

    /** 登录成功后清空该键的失败计数 */
    public void reset(String key)
    {
        counters.remove(key);
    }

    private long windowMillis()
    {
        return properties.getSecurity().getLoginWindowMinutes() * 60_000L;
    }

    private boolean expired(Counter counter)
    {
        return System.currentTimeMillis() - counter.windowStart > windowMillis();
    }
}
