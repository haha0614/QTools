package com.qtools.media.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 视频时间点标记 media_video_marker
 *
 * <p>{@code pos} 是秒（保留 2 位），应用侧校验 {@code 0 <= pos <= media_video.dur}；
 * {@code dur} 为 NULL（扫描脚本还没拿到时长）时跳过上限校验。</p>
 *
 * @author qtools
 */
public class MediaVideoMarker
{
    private Long id;

    private String videoId;

    /** 时间点（秒） */
    private BigDecimal pos;

    /** 标记名称（可空，去首尾空格后 <= 64 字符） */
    private String label;

    /** 颜色（可空，建议 #RRGGBB） */
    private String color;

    private Date createTime;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getVideoId()
    {
        return videoId;
    }

    public void setVideoId(String videoId)
    {
        this.videoId = videoId;
    }

    public BigDecimal getPos()
    {
        return pos;
    }

    public void setPos(BigDecimal pos)
    {
        this.pos = pos;
    }

    public String getLabel()
    {
        return label;
    }

    public void setLabel(String label)
    {
        this.label = label;
    }

    public String getColor()
    {
        return color;
    }

    public void setColor(String color)
    {
        this.color = color;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }
}
