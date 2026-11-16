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
 * <p><b>P6.99：没有 Authorization 头时回退到同源 cookie</b> —— {@code <img src>} 这类请求
 * 浏览器不会带自定义头，于是封面 / 头像 / 缩略图天然是「匿名请求」。登录用户看自己的
 * guidance / restricted 相册时，缩略图会被访客可见性判成不可见而 404（头像变首字、卡片没背景）。
 * 前端在登录 / 刷新时把同一个 token 写进 {@code qtools_app_token} cookie（见 useAppAuth.js），
 * 这里读它，图片请求就能拿到与页面一致的身份。cookie 与 localStorage 同源同域、都不是 HttpOnly
 * （token 本来就存在 localStorage 里，不新增暴露面），只影响 {@code /media/**} 这条读路径。</p>
 *
 * @author qtools
 */
public final class BearerTokens
{
    private static final String BEARER = "Bearer ";

    /** 与前端 useAppAuth.js 同步写入的 cookie 名（token 的第二个投递通道，专为 <img> 服务） */
    private static final String COOKIE_NAME = "qtools_app_token";

    private BearerTokens()
    {
    }

    /** @return 原始 token（不带前缀）；头与 cookie 都没有则 null */
    public static String of(HttpServletRequest request)
    {
        if (request == null)
        {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header != null)
        {
            header = header.trim();
            if (header.length() > BEARER.length() && header.regionMatches(true, 0, BEARER, 0, BEARER.length()))
            {
                String token = header.substring(BEARER.length()).trim();
                if (!token.isEmpty())
                {
                    return token;
                }
            }
        }
        return cookieToken(request);
    }

    /** 从 {@code Cookie} 头里挑 {@code qtools_app_token}（没有 / 空值 → null） */
    private static String cookieToken(HttpServletRequest request)
    {
        String raw = request.getHeader("Cookie");
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        for (String part : raw.split(";"))
        {
            int eq = part.indexOf('=');
            if (eq <= 0)
            {
                continue;
            }
            if (!COOKIE_NAME.equals(part.substring(0, eq).trim()))
            {
                continue;
            }
            String value = part.substring(eq + 1).trim();
            if (value.isEmpty())
            {
                return null;
            }
            try
            {
                return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
            }
            catch (IllegalArgumentException e)
            {
                return value;
            }
        }
        return null;
    }
}
