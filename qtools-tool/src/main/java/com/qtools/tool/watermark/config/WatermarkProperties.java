package com.qtools.tool.watermark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 去水印工具配置（前缀 qtools.watermark）
 *
 * <p>取数靠 Node 侧车（doc/watermark/douyin-parse.mjs）驱动本机 Chrome：
 * 抖音详情接口被 JS 签名（uifid/a_bogus）挡住，纯 HTTP 拿不到数据，
 * 只能让真实浏览器自己发请求、我们旁听它的响应。</p>
 *
 * @author qtools
 */
@Component
@ConfigurationProperties(prefix = "qtools.watermark")
public class WatermarkProperties
{
    /** 总开关：关闭后接口直接拒绝 */
    private boolean enabled = true;

    /** 侧车地址（Java 只和本机回环通信） */
    private String serviceUrl = "http://127.0.0.1:7788";

    /** Node 可执行文件 */
    private String nodePath = "tools/node/node.exe";

    /** 侧车脚本路径 */
    private String scriptPath = "QTools/doc/watermark/douyin-parse.mjs";

    /** 侧车未运行时是否自动拉起 */
    private boolean autoStart = true;

    /** 单次解析超时（毫秒）：冷启动含 Chrome 拉起约 8~12 秒，故给足 */
    private int parseTimeoutMs = 60000;

    /** 是否允许通过后端代理下载（CDN 有防盗链，浏览器直连会 403，默认必须代理） */
    private boolean downloadEnabled = true;

    /** 代理下载单文件上限（字节），默认 500MB */
    private long maxDownloadBytes = 500L * 1024 * 1024;

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getServiceUrl()
    {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl)
    {
        this.serviceUrl = serviceUrl;
    }

    public String getNodePath()
    {
        return nodePath;
    }

    public void setNodePath(String nodePath)
    {
        this.nodePath = nodePath;
    }

    public String getScriptPath()
    {
        return scriptPath;
    }

    public void setScriptPath(String scriptPath)
    {
        this.scriptPath = scriptPath;
    }

    public boolean isAutoStart()
    {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart)
    {
        this.autoStart = autoStart;
    }

    public boolean isDownloadEnabled()
    {
        return downloadEnabled;
    }

    public void setDownloadEnabled(boolean downloadEnabled)
    {
        this.downloadEnabled = downloadEnabled;
    }

    public long getMaxDownloadBytes()
    {
        return maxDownloadBytes;
    }

    public void setMaxDownloadBytes(long maxDownloadBytes)
    {
        this.maxDownloadBytes = maxDownloadBytes;
    }

    public int getParseTimeoutMs()
    {
        return parseTimeoutMs;
    }

    public void setParseTimeoutMs(int parseTimeoutMs)
    {
        this.parseTimeoutMs = parseTimeoutMs;
    }
}
