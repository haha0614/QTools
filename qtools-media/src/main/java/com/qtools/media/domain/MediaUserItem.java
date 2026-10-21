package com.qtools.media.domain;

import java.util.Date;

/**
 * 收藏/最近浏览 的读出视图（media_favorite / media_recent 左连媒体表补齐 title）
 *
 * @author qtools
 */
public class MediaUserItem
{
    /** video | photo */
    private String kind;

    /** video.id；photo 为 null */
    private String targetId;

    /** photo.album_id；video 为 null */
    private String albumId;

    /** photo.seq（0 基）；video 为 null */
    private Integer seq;

    /** 视频标题 或 相册标题（photo 的完整标题由服务层拼 "#<seq+1>"） */
    private String title;

    /** 续播秒数（仅最近浏览的视频） */
    private Double pos;

    private Double dur;

    /** 收藏创建时间 / 最近浏览更新时间 */
    private Date happenedAt;

    public String getKind()
    {
        return kind;
    }

    public void setKind(String kind)
    {
        this.kind = kind;
    }

    public String getTargetId()
    {
        return targetId;
    }

    public void setTargetId(String targetId)
    {
        this.targetId = targetId;
    }

    public String getAlbumId()
    {
        return albumId;
    }

    public void setAlbumId(String albumId)
    {
        this.albumId = albumId;
    }

    public Integer getSeq()
    {
        return seq;
    }

    public void setSeq(Integer seq)
    {
        this.seq = seq;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public Double getPos()
    {
        return pos;
    }

    public void setPos(Double pos)
    {
        this.pos = pos;
    }

    public Double getDur()
    {
        return dur;
    }

    public void setDur(Double dur)
    {
        this.dur = dur;
    }

    public Date getHappenedAt()
    {
        return happenedAt;
    }

    public void setHappenedAt(Date happenedAt)
    {
        this.happenedAt = happenedAt;
    }
}
