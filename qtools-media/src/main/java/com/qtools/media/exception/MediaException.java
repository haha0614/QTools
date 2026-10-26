package com.qtools.media.exception;

/**
 * 媒体接口业务异常，携带要回给客户端的 HTTP 状态码
 *
 * @author qtools
 */
public class MediaException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /** HTTP 状态码 */
    private final int status;

    public MediaException(int status, String message)
    {
        super(message);
        this.status = status;
    }

    public int getStatus()
    {
        return status;
    }

    public static MediaException badRequest(String message)
    {
        return new MediaException(400, message);
    }

    public static MediaException forbidden()
    {
        return new MediaException(403, "forbidden");
    }

    public static MediaException notFound()
    {
        return new MediaException(404, "not found");
    }

    public static MediaException notFound(String message)
    {
        return new MediaException(404, message);
    }

    /** 409：与当前状态冲突（重复扫描任务、目标已存在、共享目录不可单独删除） */
    public static MediaException conflict(String message)
    {
        return new MediaException(409, message);
    }

    public static MediaException tooManyRequests(String message)
    {
        return new MediaException(429, message);
    }

    /**
     * 503：依赖的外部能力**暂时**不可用（P5.47 本地模型未启动 / 未启用 / 超时）
     *
     * <p>与 {@link #conflict(String)} 的区别：409 是「当前状态冲突，用户自己关掉别的任务再来」，
     * 503 是「这台机器的某个依赖没就绪」，前端据此显示「本地模型未就绪（Ollama 未启动？）」。</p>
     */
    public static MediaException serviceUnavailable(String message)
    {
        return new MediaException(503, message);
    }
}
