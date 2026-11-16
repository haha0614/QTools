package com.qtools.common.security;

/**
 * 用户端 token 解析结果在 request attribute 上的键（唯一真源）
 *
 * <p>写入方是 {@code com.qtools.app.security.AppJwtFilter}；读取方除它自己，还有
 * {@code com.qtools.framework.web.resolver.CurrentAppUserArgumentResolver}。</p>
 *
 * <p>放在 common 而不是各自模块里，是因为 framework 不能依赖 qtools-app（依赖方向：
 * app → common，framework → system → common），而同一个字面量复制两份迟早会漂移——
 * 一旦漂移，表现是「过滤器写了、注解读不到」，且只在运行期暴露。</p>
 *
 * @author qtools
 */
public final class AppRequestAttributes
{
    /** 当前用户端账号 id（Long） */
    public static final String USER_ID = "appUserId";

    /** 当前会话标识 jti（String） */
    public static final String TOKEN_ID = "appTokenId";

    private AppRequestAttributes()
    {
    }
}
