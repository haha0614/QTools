package com.qtools.common.utils;

import java.io.IOException;

/**
 * 客户端断开判断工具
 *
 * <p>浏览器刷新页面、切换路由、关闭标签页，或 keepalive / sendBeacon 这类「发了就不管」的请求被取消时，
 * 服务端往已关闭的连接写响应会抛异常：</p>
 * <ul>
 * <li>Spring 6 起包成 {@code AsyncRequestNotUsableException}（内部 cause 往往是 ClientAbortException）；</li>
 * <li>Tomcat 直接抛 {@code org.apache.catalina.connector.ClientAbortException}；</li>
 * <li>底层也可能是 {@code java.io.IOException: Broken pipe / Connection reset}，Windows 上中文提示为
 * 「你的主机中的软件中止了一个已建立的连接」。</li>
 * </ul>
 *
 * <p>这些都是正常的客户端行为、不是服务端故障，不应按系统异常打 ERROR 堆栈。
 * 本工具用类名与消息特征判断，避免依赖 spring-web / tomcat 的具体类型，因此放在公共模块，
 * framework 与各业务模块都能用。</p>
 *
 * @author qtools
 */
public class ClientAbortUtils
{
    /** 异常链最大遍历深度，防御异常链自引用 */
    private static final int MAX_DEPTH = 20;

    private ClientAbortUtils()
    {
    }

    /**
     * 异常链里是否带「客户端主动断开」特征
     *
     * @param e 待判断的异常（可为 null）
     * @return true 表示客户端已断开，无需再记错误日志
     */
    public static boolean isClientAbort(Throwable e)
    {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH)
        {
            String name = cur.getClass().getName();
            if ("org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(name)
                    || name.endsWith("ClientAbortException"))
            {
                return true;
            }
            String msg = cur.getMessage();
            if (cur instanceof IOException && msg != null && !msg.isEmpty())
            {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset")
                        || lower.contains("connection aborted") || lower.contains("connection closed")
                        || msg.contains("中止") || msg.contains("已建立的连接"))
                {
                    return true;
                }
            }
            Throwable cause = cur.getCause();
            cur = (cause == cur) ? null : cause;
        }
        return false;
    }

    /**
     * 取根因的简短描述，用于单行日志（不打印堆栈）
     *
     * @param e 异常
     * @return 根因消息，最长 200 字符
     */
    public static String rootCauseMessage(Throwable e)
    {
        if (e == null)
        {
            return "";
        }
        Throwable cur = e;
        int depth = 0;
        while (cur.getCause() != null && cur.getCause() != cur && depth++ < MAX_DEPTH)
        {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null || msg.isEmpty())
        {
            return cur.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
