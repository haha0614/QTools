package com.qtools.app.exception;

/**
 * 用户端接口业务异常（携带 HTTP 状态码）
 *
 * <p>用户端契约是真实 HTTP 状态码 + {@code {"error":"..."}}，与若依后台的
 * {@code HTTP 200 + {code,msg}} 不同，两者由各自的 advice 分开处理。</p>
 *
 * @author qtools
 */
public class AppException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int status;

    public AppException(int status, String message)
    {
        super(message);
        this.status = status;
    }

    public int getStatus()
    {
        return status;
    }

    public static AppException badRequest(String message)
    {
        return new AppException(400, message);
    }

    public static AppException unauthorized(String message)
    {
        return new AppException(401, message);
    }

    public static AppException conflict(String message)
    {
        return new AppException(409, message);
    }

    public static AppException tooManyRequests(String message)
    {
        return new AppException(429, message);
    }

    public static AppException notImplemented(String message)
    {
        return new AppException(501, message);
    }
}
