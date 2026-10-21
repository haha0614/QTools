package com.qtools.media.domain;

/**
 * 媒体图片 media_photo
 *
 * @author qtools
 */
public class MediaPhoto
{
    private Long id;

    private String albumId;

    /** 册内 0 基序号 */
    private Integer seq;

    /** 相对媒体根的路径 */
    private String relPath;

    private Long bytes;

    private Integer w;

    private Integer h;

    /** 是否大图（1/0） */
    private Integer isBig;

    /** 是否隐藏（1/0）；仅管理端查询会填充 */
    private Integer hidden;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
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

    public String getRelPath()
    {
        return relPath;
    }

    public void setRelPath(String relPath)
    {
        this.relPath = relPath;
    }

    public Long getBytes()
    {
        return bytes;
    }

    public void setBytes(Long bytes)
    {
        this.bytes = bytes;
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

    public Integer getIsBig()
    {
        return isBig;
    }

    public void setIsBig(Integer isBig)
    {
        this.isBig = isBig;
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
