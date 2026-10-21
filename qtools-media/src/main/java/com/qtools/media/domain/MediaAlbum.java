package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体相册 media_album
 *
 * @author qtools
 */
public class MediaAlbum
{
    /** 相册 id，如 a001 */
    private String id;

    private Long personId;

    private String title;

    /** 相对媒体根的目录 */
    private String dir;

    private Integer photoCount;

    private Long bytes;

    /** 封面，如 thumbs/p/a001/0000.jpg */
    private String coverPath;

    private String note;

    private Integer sort;

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

    public Long getPersonId()
    {
        return personId;
    }

    public void setPersonId(Long personId)
    {
        this.personId = personId;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getDir()
    {
        return dir;
    }

    public void setDir(String dir)
    {
        this.dir = dir;
    }

    public Integer getPhotoCount()
    {
        return photoCount;
    }

    public void setPhotoCount(Integer photoCount)
    {
        this.photoCount = photoCount;
    }

    public Long getBytes()
    {
        return bytes;
    }

    public void setBytes(Long bytes)
    {
        this.bytes = bytes;
    }

    public String getCoverPath()
    {
        return coverPath;
    }

    public void setCoverPath(String coverPath)
    {
        this.coverPath = coverPath;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
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
