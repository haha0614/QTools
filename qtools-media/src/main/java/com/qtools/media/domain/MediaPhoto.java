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

    /** 册内 0 基序号（相册内插图后会整体后移，不能当身份用） */
    private Integer seq;

    /**
     * 内容哈希（oshash）—— 相册内插图 / 改名 / 移动都不变，是收藏与最近浏览的稳定身份。
     *
     * <p>同一张图可以合法地出现在多个相册里，因此本列<b>没有</b>唯一索引；老库数据可能为 null
     * （扫描脚本回填之前），此时必须回退 {@code (album_id, seq)}。详见
     * {@code doc/媒体库可移植性方案.md}。</p>
     */
    private String contentHash;

    /** 所属库根 media_root.id；null 表示默认根（多库根预留，当前只用一个根） */
    private Integer rootId;

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

    public String getContentHash()
    {
        return contentHash;
    }

    public void setContentHash(String contentHash)
    {
        this.contentHash = contentHash;
    }

    public Integer getRootId()
    {
        return rootId;
    }

    public void setRootId(Integer rootId)
    {
        this.rootId = rootId;
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
