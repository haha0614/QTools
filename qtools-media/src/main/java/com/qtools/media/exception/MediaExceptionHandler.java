package com.qtools.media.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 媒体接口统一错误体：{"error":"..."}
 *
 * <p>只作用于 com.qtools.media 下的控制器，不影响 RuoYi 既有接口的 AjaxResult 约定。</p>
 *
 * <p>必须显式提到最高优先级：framework 的 GlobalExceptionHandler 未声明 {@code @Order}，
 * 两者同为零优先级时按 bean 顺序取胜，媒体接口的 403/404/429 会被它改成 HTTP 200 +
 * {code,msg}，与只读契约不符。</p>
 *
 * @author qtools
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.qtools.media.controller")
public class MediaExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(MediaExceptionHandler.class);

    @ExceptionHandler(MediaException.class)
    public void handleMediaException(MediaException e, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (e.getStatus() >= 500)
        {
            log.warn("媒体接口异常 {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        }
        writeError(response, request, e.getStatus(), e.getMessage());
    }

    /**
     * 权限不足（管理端 {@code @PreAuthorize} 拒绝）
     *
     * <p>必须显式声明：本 advice 优先级高于 framework 的 GlobalExceptionHandler，
     * 若落到下面的 {@code Exception} 分支，会把 403 变成 500。这里保留真实 HTTP 403，
     * 但仍使用本模块的 {@code {"error":"..."}} 错误形状。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        log.warn("媒体接口权限不足 {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        writeError(response, request, 403, "forbidden");
    }

    @ExceptionHandler(Exception.class)
    public void handleException(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        log.error("媒体接口未捕获异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        writeError(response, request, 500, "internal error");
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, int status, String message) throws IOException
    {
        if (response.isCommitted())
        {
            // 已经流式输出（如转码中途失败），只能断开，不能再改状态码
            log.debug("响应已提交，忽略错误体: status={} msg={}", status, message);
            return;
        }
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        byte[] body = ("{\"error\":\"" + escape(message) + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        if (!"HEAD".equalsIgnoreCase(request.getMethod()))
        {
            response.getOutputStream().write(body);
        }
        response.flushBuffer();
    }

    /** 最小 JSON 字符串转义，只处理控制字符、引号、反斜杠 */
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
            switch (c)
            {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20)
                    {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
