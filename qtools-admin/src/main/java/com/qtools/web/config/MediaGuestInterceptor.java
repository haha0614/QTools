package com.qtools.web.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
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

    @Autowired
    private IMediaGuestAccessService mediaGuestAccessService;

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
        if (mediaGuestAccessService.hiddenRootIds().isEmpty())
        {
            // 默认状态（没有任何根收紧）：不解析目标、不查库
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
