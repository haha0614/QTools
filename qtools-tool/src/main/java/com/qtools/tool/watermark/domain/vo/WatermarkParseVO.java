package com.qtools.tool.watermark.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * 去水印解析结果
 *
 * <p>字段名就是前端直接消费的字段名，新增字段只加不改。</p>
 *
 * @author qtools
 */
public class WatermarkParseVO
{
    /** 平台名，如“抖音” */
    private String platform;

    /** 作品 ID */
    private String videoId;

    /** 标题 / 文案 */
    private String title;

    /** 作者昵称 */
    private String author;

    /** 封面图 */
    private String cover;

    /** 时长（秒） */
    private Long duration;

    /** 视频宽 */
    private Integer width;

    /** 视频高 */
    private Integer height;

    /** 可下载地址（视频单条，图文多条） */
    private List<WatermarkMediaItem> items = new ArrayList<>();

    public String getPlatform()
    {
        return platform;
    }

    public void setPlatform(String platform)
    {
        this.platform = platform;
    }

    public String getVideoId()
    {
        return videoId;
    }

    public void setVideoId(String videoId)
    {
        this.videoId = videoId;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getAuthor()
    {
        return author;
    }

    public void setAuthor(String author)
    {
        this.author = author;
    }

    public String getCover()
    {
        return cover;
    }

    public void setCover(String cover)
    {
        this.cover = cover;
    }

    public Long getDuration()
    {
        return duration;
    }

    public void setDuration(Long duration)
    {
        this.duration = duration;
    }

    public Integer getWidth()
    {
        return width;
    }

    public void setWidth(Integer width)
    {
        this.width = width;
    }

    public Integer getHeight()
    {
        return height;
    }

    public void setHeight(Integer height)
    {
        this.height = height;
    }

    public List<WatermarkMediaItem> getItems()
    {
        return items;
    }

    public void setItems(List<WatermarkMediaItem> items)
    {
        this.items = items;
    }
}
