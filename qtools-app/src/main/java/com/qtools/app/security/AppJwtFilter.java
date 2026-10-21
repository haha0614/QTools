package com.qtools.app.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 用户端 token 过滤器（只处理 {@code /api/app/**}）
 *
 * <p>刻意做成<b>普通 Servlet Filter</b>（由 {@code AppFilterConfig} 用 FilterRegistrationBean 注册），
 * 不加入 Spring Security 过滤链、不改 framework 的 SecurityConfig：用户端与后台是两套完全独立的身份体系，
 * 后台链只负责放行 {@code /api/app/**}（控制器类上 {@code @Anonymous}），"是否已登录"一律由本过滤器裁决。</p>
 *
 * <p>失败一律回 {@code 401 + {"error":"未登录或登录已过期"}}，不落成 500、不落成 302；
 * 不写 cookie、不写 session（无状态）。</p>
 *
 * @author qtools
 */
public class AppJwtFilter extends OncePerRequestFilter
{
    private static final Logger log = LoggerFactory.getLogger(AppJwtFilter.class);

    /** request attribute：当前用户 id（Long） */
    public static final String ATTR_USER_ID = "appUserId";

    /** request attribute：当前会话 jti（String），登出时用它撤销 */
    public static final String ATTR_TOKEN_ID = "appTokenId";

    /** 免登录白名单：注册、登录、发送验证码 */
    private static final Set<String> WHITELIST = Set.of(
            "/api/app/auth/register",
            "/api/app/auth/login",
            "/api/app/auth/code");

    private static final String BEARER = "Bearer ";

    private static final String UNAUTHORIZED_BODY = "{\"error\":\"未登录或登录已过期\"}";

    private final AppJwtService appJwtService;

    public AppJwtFilter(AppJwtService appJwtService)
    {
        this.appJwtService = appJwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        // CORS 预检不带 Authorization，放行给 framework 的 CorsFilter 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()))
        {
            chain.doFilter(request, response);
            return;
        }
        if (WHITELIST.contains(path(request)))
        {
            chain.doFilter(request, response);
            return;
        }
        String token = bearerToken(request);
        if (token == null)
        {
            unauthorized(request, response);
            return;
        }
        AppJwtService.Principal principal = appJwtService.verify(token);
        if (principal == null)
        {
            log.debug("用户端 token 无效 uri={}", request.getRequestURI());
            unauthorized(request, response);
            return;
        }
        request.setAttribute(ATTR_USER_ID, principal.getUserId());
        request.setAttribute(ATTR_TOKEN_ID, principal.getJti());
        chain.doFilter(request, response);
    }

    /** 当前请求的用户 id（未经过本过滤器的路径可能为 null） */
    public static Long currentUserId(HttpServletRequest request)
    {
        Object value = request.getAttribute(ATTR_USER_ID);
        return value instanceof Long ? (Long) value : null;
    }

    /** 当前请求的会话 jti */
    public static String currentTokenId(HttpServletRequest request)
    {
        Object value = request.getAttribute(ATTR_TOKEN_ID);
        return value instanceof String ? (String) value : null;
    }

    /** 取 {@code Authorization: Bearer <token>}；缺失或格式不对返回 null */
    private static String bearerToken(HttpServletRequest request)
    {
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

    /** 去掉可能的末尾斜杠，便于精确匹配白名单 */
    private static String path(HttpServletRequest request)
    {
        String uri = request.getRequestURI();
        if (uri == null)
        {
            return "";
        }
        int semicolon = uri.indexOf(';');
        if (semicolon >= 0)
        {
            uri = uri.substring(0, semicolon);
        }
        while (uri.length() > 1 && uri.endsWith("/"))
        {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        // 本过滤器跑在 framework 的 CorsFilter 之前，401 需要自己补上跨域头，否则浏览器只能看到 CORS 错误
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty())
        {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        byte[] body = UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
