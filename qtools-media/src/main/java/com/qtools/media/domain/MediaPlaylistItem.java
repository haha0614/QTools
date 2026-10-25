package com.qtools.media.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 播放列表条目 media_playlist_item（P5-B）
 *
 * <p>{@code seq} 是显式顺序列（0 起、同一列表内连续）：主键 {@code (playlist_id, seq)} 保证
 * 同一列表内位置不重复，唯一键 {@code (playlist_id, video_id)} 保证同一视频只出现一次。</p>
 *
 * <p>{@code title} / {@code dur} 是<b>读列表详情时 join media_video 的投影列</b>，不在本表里存：
 * 视频改名或补齐时长后，列表详情自动跟着变，不必回写条目。视频被软删 / 移走后这两个字段为 null，
 * 条目本身仍然保留（前端显示「文件缺失」而不是整条消失）。</p>
 *
 * @author qtools
 */
public class MediaPlaylistItem
{
    /** 所属列表 media_playlist.id */
    private Long playlistId;

    /** 视频 media_video.id（VARCHAR(16)） */
    private String videoId;

    /** 顺序（0 起，同一列表内连续） */
    private Integer seq;

    /** 视频标题（join media_video 的投影列） */
    private String title;

    /** 时长秒（join media_video 的投影列） */
    private BigDecimal dur;

    private Date createTime;

    public Long getPlaylistId()
    {
        return playlistId;
    }

    public void setPlaylistId(Long playlistId)
    {
        this.playlistId = playlistId;
    }

    public String getVideoId()
    {
        return videoId;
    }

    public void setVideoId(String videoId)
    {
        this.videoId = videoId;
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

    public BigDecimal getDur()
    {
        return dur;
    }

    public void setDur(BigDecimal dur)
    {
        this.dur = dur;
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
