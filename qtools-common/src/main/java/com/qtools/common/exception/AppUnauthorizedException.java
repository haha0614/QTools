package com.qtools.common.exception;

/**
 * 用户端未登录 / 会话已失效（401）
 *
 * <p>为什么定义在 common 而不是 qtools-app：抛出方之一是 {@code qtools-framework} 的参数解析器
 * （{@code @CurrentAppUser} 解析不到账号时），而 framework <b>不能</b>依赖 qtools-app。
 * 转成对外响应的地方有两处，各自负责自己的辖域：</p>
 *
 * <ul>
 *   <li>{@code com.qtools.app.exception.AppExceptionHandler}（只覆盖 {@code com.qtools.app.controller}）
 *       —— 转成用户端契约 {@code 401 + {"error":"..."}}；</li>
 *   <li>{@code com.qtools.framework.web.exception.GlobalExceptionHandler}（其余控制器）
 *       —— 同样的 401 + {@code {"error":"..."}}，避免落到若依的 {@code HTTP 200 + {code,msg}}。</li>
 * </ul>
 *
 * @author qtools
 */
public class AppUnauthorizedException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public AppUnauthorizedException(String message)
    {
        super(message);
    }
}
