package com.qtools.app.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.app.mapper.AppUserMapper;
import com.qtools.common.security.IAppRoleResolver;

/**
 * {@link IAppRoleResolver} 的唯一实现（P5.89）：{@code app_user.role} + 30s 本地缓存
 *
 * <p>为什么不用 token claim 带 role：改角色必须<b>立刻</b>生效，而 JWT 一旦签发就改不了 ——
 * 把 role 写进 claim 意味着「降级站长」要等 7 天 token 过期，或者把该账号所有会话撤销（把人踢下线）。
 * 查库 + 30s 缓存没有这两个代价，与 {@code media.userEditing} 的读取策略完全一致；
 * 管理端改角色时还会主动 {@link #invalidate(Long)}，所以那条路径上是 0 延迟。</p>
 *
 * <p>读的只有一列（{@code select role from app_user where id=?}），且缓存 30s ——
 * 站端写接口每次最多一次这种查询，不构成热点。</p>
 *
 * @author qtools
 */
@Component
public class AppRoleResolver implements IAppRoleResolver
{
    private static final Logger log = LoggerFactory.getLogger(AppRoleResolver.class);

    /** 本地缓存有效期：30s 内同一账号不查库（与 media.userEditing 的 30s 同档） */
    private static final long CACHE_TTL_MS = 30_000L;

    @Autowired
    private AppUserMapper appUserMapper;

    /** userId → 缓存项；单实例自托管场景，不需要更重的结构 */
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @Override
    public String roleOf(Long userId)
    {
        if (userId == null)
        {
            return ROLE_USER;
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(userId);
        if (cached != null && now - cached.at < CACHE_TTL_MS)
        {
            return cached.role;
        }
        String role = normalize(appUserMapper.selectRoleById(userId));
        cache.put(userId, new Cached(role, now));
        return role;
    }

    /** 清掉某账号的缓存（管理端改角色后立刻生效，不让用户等 30s） */
    public void invalidate(Long userId)
    {
        if (userId != null)
        {
            cache.remove(userId);
        }
    }

    /** 库里的值 → 两档之一；空 / 脏值一律回普通用户（并只 warn 一次，不抛异常） */
    private static String normalize(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return ROLE_USER;
        }
        String clean = raw.trim().toLowerCase();
        if (ROLE_OWNER.equals(clean) || ROLE_USER.equals(clean))
        {
            return clean;
        }
        log.warn("app_user.role 的脏值（{}），按普通用户处理", raw);
        return ROLE_USER;
    }

    private static final class Cached
    {
        private final String role;

        private final long at;

        Cached(String role, long at)
        {
            this.role = role;
            this.at = at;
        }
    }
}
