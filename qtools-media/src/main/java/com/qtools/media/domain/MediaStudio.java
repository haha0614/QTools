package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体工作室 media_studio（制片商 / 厂牌）
 *
 * <p>P5-A 新增的一等实体：{@code media_video_studio(video_id, studio_id)} 是权威关联，
 * 索引 {@code videos[].studios} 输出「工作室名数组」（非空才输出），因此不需要在
 * {@code media_video} 上加冗余列。</p>
 *
 * <p>{@code useCount} 是冗余计数（引用它的 {@code media_video_studio} 行数），写入时按实际行数重算；
 * {@code videoCount} 只在管理端 / 站端列表与详情里回填，不落库。</p>
 *
 * @author qtools
 */
public class MediaStudio
{
    private Long id;

    /** 工作室名（去首尾空格后 1-64 字符，库内唯一 —— 列宽 120 留了余量） */
    private String name;

    /** 别名（可空，<= 128 字符） */
    private String alias;

    /** 备注（可空，<= 512 字符） */
    private String note;

    /** 头像 / Logo 路径（可空，<= 400；站内相对路径或 http(s) URL） */
    private String avatarPath;

    /** 引用次数（media_video_studio 中 studio_id = 本 id 的行数，写入时重算） */
    private Integer useCount;

    /** 排序（升序在前） */
    private Integer sort;

    /** 1 = 隐藏（站端列表 / 索引不输出） */
    private Integer hidden;

    private Date createdAt;

    /** 只读回填：该工作室名下的视频数（不落库） */
    private Long videoCount;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getAlias()
    {
        return alias;
    }

    public void setAlias(String alias)
    {
        this.alias = alias;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public String getAvatarPath()
    {
        return avatarPath;
    }

    public void setAvatarPath(String avatarPath)
    {
        this.avatarPath = avatarPath;
    }

    public Integer getUseCount()
    {
        return useCount;
    }

    public void setUseCount(Integer useCount)
    {
        this.useCount = useCount;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public Integer getHidden()
    {
        return hidden;
    }

    public void setHidden(Integer hidden)
    {
        this.hidden = hidden;
    }

    public Date getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt)
    {
        this.createdAt = createdAt;
    }

    public Long getVideoCount()
    {
        return videoCount;
    }

    public void setVideoCount(Long videoCount)
    {
        this.videoCount = videoCount;
    }
}
