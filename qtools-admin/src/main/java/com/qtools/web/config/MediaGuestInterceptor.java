package com.qtools.web.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import com.qtools.common.utils.StringUtils;
import com.qtools.framework.web.service.TokenService;
import com.qtools.media.service.IMediaGuestAccessService;

/**
 * 未登录可见性拦截器（P5.78）—— 站端「仅登录可见」库根的<b>唯一</b> 404 出口
 *
 * <p>为什么要拦截器而不是在每个 controller 里 if：判定要覆盖 10 个 URL 形态
 * （视频详情 / 元信息 / 封面 / 精灵图 / 预览 / 取帧 / 相册 / 内容寻址缩略图 / 老序号缩略图 / 原文件转码），
 * 而且以后新增站点接口时更容易被漏掉。拦截器拿到的是 Spring 已经解析好的
 * {@code BEST_MATCHING_PATTERN_ATTRIBUTE} 与模板变量，因此不依赖任何手写 URL 解析，
 * 目标 → 根 的映射规则集中在 {@link IMediaGuestAccessService#isBlocked} 一处。</p>
 *
 * <p><b>为什么是 404 而不是 403</b>：403 等于确认「这个 id 存在但你不能看」，
 * 对未登录访客而言这与索引里查不到的口径不一致，会暴露存在性。这里回 404 且错误体形状
 * 与 {@code MediaException.notFound()} 完全一致（{@code {"error":"not found"}}），
 * 与「本来就没有这个 id」不可区分。</p>
 *
 * <p>{@code /api/media/admin/**} 不经过本拦截器：管理端走 Security 与 {@code @PreAuthorize}，
 * 它的 token 是若依的管理端 token，不是 app token。</p>
 *
 * @author qtools
 */
@Component
public class MediaGuestInterceptor implements HandlerInterceptor
{
    private static final Logger log = LoggerFactory.getLogger(MediaGuestInterceptor.class);

    /** 管理端前缀（站端判定的排除项） */
    private static final String ADMIN_PATTERN_PREFIX = "/media/admin";

    /** 管理端 SPA 存后台 token 的 cookie 名（QTools-M/src/utils/auth.js 的 TokenKey） */
    private static final String ADMIN_TOKEN_COOKIE = "Admin-Token";

    @Autowired
    private IMediaGuestAccessService mediaGuestAccessService;

    @Autowired
    private TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException
    {
        if (!(handler instanceof HandlerMethod))
        {
            return true;
        }
        Object patternAttr = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String pattern = patternAttr instanceof String ? (String) patternAttr : null;
        if (pattern == null || pattern.startsWith(ADMIN_PATTERN_PREFIX)
                || pattern.startsWith("/api" + ADMIN_PATTERN_PREFIX))
        {
            return true;
        }
        if (!mediaGuestAccessService.isGuest(request))
        {
            return true;
        }
        if (adminLoggedIn(request))
        {
            // 管理端不受分级/库根限制：<img>、<video> 发不出 Authorization，但浏览器会带同源 cookie，
            // 管理端登录态就在 Admin-Token 里。cookie 无效、过期、被篡改 → getLoginUserByToken 返回 null，
            // 照旧按访客判定，不额外放行任何东西。
            return true;
        }
        if (!mediaGuestAccessService.hasGuestRestriction())
        {
            // 默认状态（既没有收紧的库根，也没有收紧的分级）：不解析目标、不查库
            return true;
        }
        Object varsAttr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        @SuppressWarnings("unchecked")
        Map<String, String> vars = varsAttr instanceof Map ? (Map<String, String>) varsAttr : null;
        if (!mediaGuestAccessService.isBlocked(request, pattern, vars))
        {
            return true;
        }
        log.debug("未登录访客命中仅登录可见库根，回 404: {} {}", request.getMethod(), request.getRequestURI());
        writeNotFound(response, request);
        return false;
    }

    /**
     * 管理端是否已登录：只看 {@code Admin-Token} cookie
     *
     * <p>为什么不看 {@code Authorization}：用户端的 app token 走的也是这个头，
     * 拿它去 {@link TokenService#getLoginUserByToken} 解析会在每个访问都刷一行解析失败日志；
     * 而管理端的图片/视频标签请求根本带不了头，真正能指望的只有 cookie。</p>
     *
     * <p>代价说清楚：同一个浏览器登录过管理端后，它访问站端时也会带上这个 cookie，
     * 于是这台浏览器在站端也能看到限制级内容 —— 对单人自托管站点是想要的效果，
     * 换终端（手机、别人的浏览器）依然是访客。</p>
     */
    private boolean adminLoggedIn(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0)
        {
            return false;
        }
        for (Cookie cookie : cookies)
        {
            if (ADMIN_TOKEN_COOKIE.equals(cookie.getName()) && StringUtils.isNotEmpty(cookie.getValue()))
            {
                return tokenService.getLoginUserByToken(cookie.getValue()) != null;
            }
        }
        return false;
    }

    /** 与 MediaExceptionHandler 的错误体逐字节同形：{"error":"not found"} */
    private static void writeNotFound(HttpServletResponse response, HttpServletRequest request) throws IOException
    {
        byte[] body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(body.length);
        if (!"HEAD".equalsIgnoreCase(request.getMethod()))
        {
            response.getOutputStream().write(body);
        }
    }
}
