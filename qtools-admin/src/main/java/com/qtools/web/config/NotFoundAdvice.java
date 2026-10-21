package com.qtools.web.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.qtools.common.core.domain.AjaxResult;

/**
 * 资源/接口未命中统一返回 JSON 404。
 *
 * <p>framework 的 GlobalExceptionHandler 兜 Exception 会把它变成 500 且 status=200，
 * 这里用更高优先级把静态资源 404 明确成 404 + JSON，顺带带上请求路径作为说明
 * （没构建前端时 {@code /} 也能看出是缺构建产物而不是接口出错）。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class NotFoundAdvice
{
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<AjaxResult> handleNoResource(NoResourceFoundException e, HttpServletRequest request)
    {
        String uri = request.getRequestURI();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(AjaxResult.error(HttpStatus.NOT_FOUND.value(), "资源不存在：" + uri));
    }
}
