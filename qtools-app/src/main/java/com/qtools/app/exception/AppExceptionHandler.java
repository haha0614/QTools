package com.qtools.app.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 用户端接口统一错误体：{@code {"error":"..."}} + 真实 HTTP 状态码
 *
 * <p>只作用于 {@code com.qtools.app.controller}，不影响若依既有接口。</p>
 *
 * <p>必须显式提到最高优先级：framework 的 GlobalExceptionHandler 未声明 {@code @Order}，
 * 同为零优先级时按 bean 顺序取胜，会把本模块的 400/401/409/429/501 改成
 * {@code HTTP 200 + {code,msg}}，与用户端契约不符（M1/M3 已踩过同样的坑）。</p>
 *
 * @author qtools
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.qtools.app.controller")
public class AppExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(AppExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public void handleAppException(AppException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        if (e.getStatus() >= 500)
        {
            log.warn("用户端接口异常 {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        }
        writeError(request, response, e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void handleException(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException
    {
        log.error("用户端接口未捕获异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        writeError(request, response, 500, "internal error");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException
    {
        if (response.isCommitted())
        {
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
