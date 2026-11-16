package com.qtools.tool.watermark.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 去水印解析入参
 *
 * <p>请求体形如：{"url": "https://v.douyin.com/xxxxxxx/"}</p>
 *
 * @author qtools
 */
public class WatermarkParseDTO
{
    /** 分享链接（可直接粘贴“复制此链接，打开抖音”的整段分享文案） */
    @NotBlank(message = "链接不能为空")
    private String url;

    public String getUrl()
    {
        return url;
    }

    public void setUrl(String url)
    {
        this.url = url;
    }

    @Override
    public String toString()
    {
        return "WatermarkParseDTO [url=" + url + "]";
    }
}
