package com.qtools.tool.watermark.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qtools.common.annotation.Anonymous;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.exception.ServiceException;
import com.qtools.tool.watermark.config.WatermarkProperties;
import com.qtools.tool.watermark.domain.dto.WatermarkParseDTO;
import com.qtools.tool.watermark.registry.ParserRegistry;
import com.qtools.tool.watermark.spi.WatermarkParser;
import com.qtools.tool.watermark.support.MediaDownloadProxy;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 去水印工具 对外公开接口
 *
 * <p>类上标注 {@link Anonymous}，framework 的 PermitAllUrlProperties 会自动把本控制器下的
 * 全部接口加入 Security 的 permitAll 白名单，无需改动 SecurityConfig。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/watermark")
public class WatermarkController
{
    private static final Logger log = LoggerFactory.getLogger(WatermarkController.class);

    @Autowired
    private ParserRegistry parserRegistry;

    @Autowired
    private WatermarkProperties properties;

    @Autowired
    private MediaDownloadProxy downloadProxy;

    /**
     * 解析无水印地址
     *
     * <p>请求体：{"url": "分享链接或整段分享文案"}；
     * 响应 data 结构见 {@link com.qtools.tool.watermark.domain.vo.WatermarkParseVO}。</p>
     *
     * @param dto 解析入参
     * @return 解析结果
     */
    @PostMapping("/parse")
    public AjaxResult parse(@Validated @RequestBody WatermarkParseDTO dto)
    {
        if (!properties.isEnabled())
        {
            throw new ServiceException("去水印工具未开启");
        }
        WatermarkParser parser = parserRegistry.find(dto.getUrl());
        log.info("去水印解析 {}：{}", parser.platformName(), dto.getUrl());
        return AjaxResult.success(parser.parse(dto.getUrl().trim()));
    }
    /**
     * 代理下载：浏览器直连 CDN 会被防盗链 403，这里由后端带 Referer 回源后流式转发
     *
     * <p>参数用 query 而不是 JSON，方便前端直接 `window.open` / `a[download]`。</p>
     *
     * @param url 远端直链（必须在白名单域名内）
     * @param name 建议文件名（不含扩展名），可空
     * @param response 当前响应
     */
    @GetMapping("/download")
    public void download(@RequestParam("url") String url,
                         @RequestParam(value = "name", required = false) String name,
                         HttpServletResponse response)
    {
        if (!properties.isEnabled() || !properties.isDownloadEnabled())
        {
            throw new ServiceException("去水印工具未开启");
        }
        downloadProxy.proxy(url, name, response);
    }
}
