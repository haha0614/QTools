package com.qtools.framework.web.exception;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.qtools.common.constant.HttpStatus;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.core.text.Convert;
import com.qtools.common.exception.DemoModeException;
import com.qtools.common.exception.ServiceException;
import com.qtools.common.utils.ClientAbortUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.common.utils.html.EscapeUtil;

/**
 * 全局异常处理器
 * 
 * @author ruoyi
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 权限校验异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public AjaxResult handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',权限校验失败'{}'", requestURI, e.getMessage());
        return AjaxResult.error(HttpStatus.FORBIDDEN, "没有权限，请联系管理员授权");
    }

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
            HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException e, HttpServletRequest request)
    {
        log.error(e.getMessage(), e);
        Integer code = e.getCode();
        return StringUtils.isNotNull(code) ? AjaxResult.error(code, e.getMessage()) : AjaxResult.error(e.getMessage());
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public AjaxResult handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        String value = Convert.toStr(e.getValue());
        if (StringUtils.isNotEmpty(value))
        {
            value = EscapeUtil.clean(value);
        }
        log.error("请求参数类型不匹配'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(String.format("请求参数类型不匹配，参数[%s]要求类型为：'%s'，但输入值为：'%s'", e.getName(), e.getRequiredType().getName(), value));
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 客户端提前断开：刷新页面、切换路由、关闭标签页，或 keepalive / sendBeacon 请求被浏览器取消。
     *
     * <p>此时连接已不可用，写不回任何响应；这属于正常的浏览器行为，不是服务端故障。
     * 若交给下面的 {@code handleException} 会按系统异常打整段 ERROR 堆栈，一次翻页就刷满日志，
     * 因此单独处理、按 DEBUG 记录且不打堆栈。</p>
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e, HttpServletRequest request)
    {
        log.debug("客户端已断开，忽略响应写入失败：{}（{}）", request.getRequestURI(), rootCauseMessage(e));
    }

    /**
     * 异常链里是否带「客户端主动断开」特征（实现见 {@link ClientAbortUtils}，
     * 放在公共模块以便各业务模块共用；判断基于类名与消息，不引入 spring-web / tomcat 的类型依赖）。
     */
    public static boolean isClientAbort(Throwable e)
    {
        return ClientAbortUtils.isClientAbort(e);
    }

    /** 取根因的简短信息，避免日志里塞整段堆栈 */
    private static String rootCauseMessage(Throwable e)
    {
        return ClientAbortUtils.rootCauseMessage(e);
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        if (isClientAbort(e))
        {
            // 连接已断，写响应必然再次失败；只留一行 DEBUG，避免刷 ERROR 堆栈
            log.debug("客户端已断开，忽略：{}", requestURI);
            return null;
        }
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public AjaxResult handleBindException(BindException e)
    {
        String message = e.getAllErrors().isEmpty() ? "参数绑定失败" : e.getAllErrors().get(0).getDefaultMessage();
        log.warn("参数绑定失败：{}", message);
        return AjaxResult.error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 自定义验证异常
     *
     * <p>参数校验失败是「调用方传错了」，不是服务端故障：用 400 而不是默认的 500，
     * 并且只记一行 WARN（不打堆栈）——否则每次前端传错字段都会在日志里刷一大段堆栈。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleMethodArgumentNotValidException(MethodArgumentNotValidException e)
    {
        String message = e.getBindingResult().getFieldError() == null ? "参数校验不通过"
                : e.getBindingResult().getFieldError().getDefaultMessage();
        log.warn("参数校验不通过：{}", message);
        return AjaxResult.error(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 演示模式异常
     */
    @ExceptionHandler(DemoModeException.class)
    public AjaxResult handleDemoModeException(DemoModeException e)
    {
        return AjaxResult.error("演示模式，不允许操作");
    }
}
