package com.qtools.media.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 媒体视频 media_video
 *
 * @author qtools
 */
public class MediaVideo
{
    /** sha1(rel)[:12] */
    private String id;

    /** 一级目录（分类） */
    private String folder;

    /** 相对媒体根的路径 */
    private String relPath;

    private String title;

    private Long size;

    /** 时长（秒） */
    private BigDecimal dur;

    private Integer w;

    private Integer h;

    private String vcodec;

    /** 浏览器能否直接播放（1/0） */
    private Integer browserOk;

    /** 相对 media 根的海报路径，如 thumbs/v/xxx.jpg */
    private String posterPath;

    /** 相对 media 根的文档路径 */
    private String docPath;

    /** 相对 media 根的字幕路径 */
    private String subPath;

    private Date mtime;

    /** 是否隐藏（1/0）；仅管理端查询会填充 */
    private Integer hidden;

    public String getId()
    {
        return id;
    }

    public void setId(String id)
    {
        this.id = id;
    }

    public String getFolder()
    {
        return folder;
    }

    public void setFolder(String folder)
    {
        this.folder = folder;
    }

    public String getRelPath()
    {
        return relPath;
    }

    public void setRelPath(String relPath)
    {
        this.relPath = relPath;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public Long getSize()
    {
        return size;
    }

    public void setSize(Long size)
    {
        this.size = size;
    }

    public BigDecimal getDur()
    {
        return dur;
    }

    public void setDur(BigDecimal dur)
    {
        this.dur = dur;
    }

    public Integer getW()
    {
        return w;
    }

    public void setW(Integer w)
    {
        this.w = w;
    }

    public Integer getH()
    {
        return h;
    }

    public void setH(Integer h)
    {
        this.h = h;
    }

    public String getVcodec()
    {
        return vcodec;
    }

    public void setVcodec(String vcodec)
    {
        this.vcodec = vcodec;
    }

    public Integer getBrowserOk()
    {
        return browserOk;
    }

    public void setBrowserOk(Integer browserOk)
    {
        this.browserOk = browserOk;
    }

    public String getPosterPath()
    {
        return posterPath;
    }

    public void setPosterPath(String posterPath)
    {
        this.posterPath = posterPath;
    }

    public String getDocPath()
    {
        return docPath;
    }

    public void setDocPath(String docPath)
    {
        this.docPath = docPath;
    }

    public String getSubPath()
    {
        return subPath;
    }

    public void setSubPath(String subPath)
    {
        this.subPath = subPath;
    }

    public Date getMtime()
    {
        return mtime;
    }

    public void setMtime(Date mtime)
    {
        this.mtime = mtime;
    }

    public Integer getHidden()
    {
        return hidden;
    }

    public void setHidden(Integer hidden)
    {
        this.hidden = hidden;
    }
}
