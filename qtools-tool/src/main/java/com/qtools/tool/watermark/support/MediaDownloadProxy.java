package com.qtools.tool.watermark.support;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.qtools.common.exception.ServiceException;
import com.qtools.tool.watermark.config.WatermarkProperties;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 媒体直链代理
 *
 * <p>为什么必须代理：抖音 CDN（*.douyinvod.com 等）有防盗链，浏览器直接打开直链不带
 * 来自 www.douyin.com 的 Referer 会被 403 拒绝；而浏览器又禁止前端伪造 Referer。
 * 所以由后端带上 UA + Referer 回源拉流，再原样吐给浏览器。</p>
 *
 * <p>安全边界：只允许 https 且域名在 {@link #ALLOWED_HOST_SUFFIXES} 白名单内，
 * 否则这个接口就是一个人人可用的开放代理。</p>
 *
 * @author qtools
 */
@Component
public class MediaDownloadProxy
{
    private static final Logger log = LoggerFactory.getLogger(MediaDownloadProxy.class);

    /** 允许代理的 CDN 域名后缀 */
    private static final List<String> ALLOWED_HOST_SUFFIXES = List.of(
            "douyinvod.com", "douyin.com", "amemv.com", "snssdk.com", "byteicdn.com", "douyinpic.com", "zjhj.com");

    /** 回源用的伪装头：与浏览器里播放时一致 */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String REFERER = "https://www.douyin.com/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Autowired
    private WatermarkProperties properties;

    /**
     * 把远端媒体流式转发给浏览器
     *
     * @param url 远端直链
     * @param name 建议文件名（不含扩展名）
     * @param response 当前响应
     */
    public void proxy(String url, String name, HttpServletResponse response)
    {
        URI uri = validate(url);
        String extension = guessExtension(uri);

        HttpResponse<InputStream> remote;
        try
        {
            remote = httpClient.send(HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("下载被中断");
        }
        catch (Exception e)
        {
            throw new ServiceException("回源失败：" + e.getMessage());
        }

        if (remote.statusCode() != 200)
        {
            log.warn("回源失败 {} -> {}", uri, remote.statusCode());
            throw new ServiceException("媒体源返回 " + remote.statusCode() + "，直链可能已过期，请重新解析");
        }

        long contentLength = remote.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > properties.getMaxDownloadBytes())
        {
            throw new ServiceException("文件过大（" + contentLength + " 字节），超过代理上限");
        }

        response.setContentType(remote.headers().firstValue("Content-Type").orElse("application/octet-stream"));
        if (contentLength > 0)
        {
            response.setContentLengthLong(contentLength);
        }
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + sanitize(name) + extension + "\"");

        try (InputStream in = remote.body(); OutputStream out = response.getOutputStream())
        {
            byte[] buffer = new byte[64 * 1024];
            long transferred = 0L;
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                out.write(buffer, 0, read);
                transferred += read;
                if (transferred > properties.getMaxDownloadBytes())
                {
                    log.warn("下载超过上限，已中断：{}", uri);
                    break;
                }
            }
            out.flush();
        }
        catch (Exception e)
        {
            log.warn("转发中断 {}：{}", uri, e.getMessage());
        }
    }

    /**
     * 校验目标地址：必须 https 且域名在白名单内
     *
     * @param url 远端直链
     * @return 解析后的 URI
     */
    private URI validate(String url)
    {
        URI uri;
        try
        {
            uri = URI.create(url.trim());
        }
        catch (Exception e)
        {
            throw new ServiceException("直链格式不合法");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()))
        {
            throw new ServiceException("只允许 https 直链");
        }
        String host = uri.getHost();
        if (host == null)
        {
            throw new ServiceException("直链缺少域名");
        }
        String lower = host.toLowerCase();
        for (String suffix : ALLOWED_HOST_SUFFIXES)
        {
            if (lower.equals(suffix) || lower.endsWith("." + suffix))
            {
                return uri;
            }
        }
        throw new ServiceException("不允许代理该域名：" + host);
    }

    /**
     * 按域名猜扩展名（视频走 mp4，图片走 jpg）
     *
     * @param uri 目标地址
     * @return 扩展名，含点
     */
    private String guessExtension(URI uri)
    {
        String host = String.valueOf(uri.getHost()).toLowerCase();
        return host.contains("douyinpic") ? ".jpg" : ".mp4";
    }

    /**
     * 去掉文件名里的非法字符
     *
     * @param name 原始名称
     * @return 安全名称
     */
    private String sanitize(String name)
    {
        if (name == null || name.isBlank())
        {
            return "douyin";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray())
        {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ')
            {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.length() > 60)
        {
            cleaned = cleaned.substring(0, 60);
        }
        return cleaned.isEmpty() ? "douyin" : cleaned;
    }
}
