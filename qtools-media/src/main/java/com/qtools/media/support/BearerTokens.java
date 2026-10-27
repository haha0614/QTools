package com.qtools.media.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取 {@code Authorization: Bearer <token>}（P5.74）
 *
 * <p>只做「把头拆成 token」这一件事，不做任何校验：校验归 {@code com.qtools.common.security.IAppTokenVerifier}
 * （由 qtools-app 实现，媒体模块不碰用户端 JWT 的密钥与 claim）。</p>
 *
 * <p>大小写不敏感地匹配 {@code Bearer}（与 {@code AppJwtFilter} 同一口径），前后空白都裁掉；
 * 头缺失、方案不是 Bearer、token 为空一律返回 null —— 调用方据此走匿名设备态，不回 401。</p>
 *
 * @author qtools
 */
public final class BearerTokens
{
    private static final String BEARER = "Bearer ";

    private BearerTokens()
    {
    }

    /** @return 原始 token（不带前缀）；没有则 null */
    public static String of(HttpServletRequest request)
    {
        if (request == null)
        {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header == null)
        {
            return null;
        }
        header = header.trim();
        if (header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0, BEARER.length()))
        {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
