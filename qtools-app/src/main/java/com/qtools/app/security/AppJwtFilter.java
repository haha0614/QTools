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

    /**
     * 管理端前缀：{@code /api/app/admin/**} 是<b>后台管理员</b>接口
     *
     * <p>它们与用户端身份体系无关：鉴权由 Spring Security 的后台 token 链 +
     * {@code @PreAuthorize("...app:user:*")} 裁决（SecurityConfig 里把该前缀显式声明为
     * {@code authenticated()}）。本过滤器只裁决用户端 token，因此对这段路径直接放行 ——
     * 否则管理员带着后台 token 调用时会被这里拦成 401 用户端未登录。</p>
     */
    private static final String ADMIN_PREFIX = "/api/app/admin/";

    private static final String BEARER = "Bearer ";

    private static final String UNAUTHORIZED_BODY = "{\"error\":\"未登录或登录已过期\"}";

    private final AppJwtService appJwtService;

    /** 审核状态判定（pending / rejected 一律拒绝，P5.79） */
    private final AppUserGuard appUserGuard;

    public AppJwtFilter(AppJwtService appJwtService, AppUserGuard appUserGuard)
    {
        this.appJwtService = appJwtService;
        this.appUserGuard = appUserGuard;
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
        String path = path(request);
        if (WHITELIST.contains(path) || path.startsWith(ADMIN_PREFIX))
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
        // P5.79：token 有效不等于可进站 —— 审核状态未通过（pending / rejected）在这里统一拦掉。
        // 只此一处：所有需要登录的用户端接口都经过本过滤器，不在各 controller 里重复 if。
        String blocked = appUserGuard.blockReasonOf(principal.getUserId());
        if (blocked != null)
        {
            log.debug("用户端账号未通过审核 userId={} uri={} reason={}", principal.getUserId(), request.getRequestURI(), blocked);
            rejected(request, response, blocked);
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

    /**
     * 账号未通过审核（pending / rejected）：403 + {@code {"error":"..."}}
     *
     * <p>刻意不用 401：401 会让前端的「会话失效」钩子清掉 token —— 这是账号状态问题，
     * 不是会话过期，清 token 会把「请去等审核」误导成「重新登录」。</p>
     */
    private static void rejected(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException
    {
        writeJson(request, response, HttpServletResponse.SC_FORBIDDEN,
                "{\"error\":\"" + escape(message) + "\"}");
    }

    private static void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        writeJson(request, response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_BODY);
    }

    /** 过滤器直出 JSON（含跨域头）：本过滤器跑在 framework 的 CorsFilter 之前 */
    private static void writeJson(HttpServletRequest request, HttpServletResponse response, int status, String json)
            throws IOException
    {
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        // 本过滤器跑在 framework 的 CorsFilter 之前，错误响应需要自己补上跨域头，否则浏览器只能看到 CORS 错误
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty())
        {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /** 最小 JSON 字符串转义（文案里可能带审核备注） */
    private static String escape(String text)
    {
        if (text == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '"' || c == '\\')
            {
                sb.append('\\').append(c);
            }
            else if (c < 0x20)
            {
                sb.append(String.format("\\u%04x", (int) c));
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
