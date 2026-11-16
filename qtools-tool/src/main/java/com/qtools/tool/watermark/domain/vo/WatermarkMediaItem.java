package com.qtools.tool.watermark.domain.vo;

/**
 * 去水印解析出的单条媒体地址
 *
 * @author qtools
 */
public class WatermarkMediaItem
{
    /** 媒体类型：video / image */
    private String type;

    /** 档位标签，如“1080p”“默认清晰度”；图文为空 */
    private String label;

    /** 直链（已做过无水印化处理） */
    private String url;

    public WatermarkMediaItem()
    {
    }

    public WatermarkMediaItem(String type, String label, String url)
    {
        this.type = type;
        this.label = label;
        this.url = url;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    public String getUrl()
    {
        return url;
    }

    public void setUrl(String url)
    {
        this.url = url;
    }
}
