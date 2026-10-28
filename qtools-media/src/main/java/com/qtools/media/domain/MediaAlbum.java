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

    /**
     * 规范化相对目录 —— 相册的稳定身份（扫描脚本维护，重扫时按它认回原 id）。
     *
     * <p>注意：本库的相册是<b>按文件名前缀分册</b>的（156 本相册只对应 13 个目录，
     * 其中「写真/念念 Nian Nian」一个目录下有 144 本），因此 {@code dir_key} 只能定位
     * "该册图片所在目录"，<b>不唯一</b>——join / 去重必须再带上 title 或直接用 id。
     * 当前 Java 读路径仍以 {@link #id} 为准，本字段只供扫描脚本与后续迁移使用。</p>
     */
    private String dirKey;

    private Integer photoCount;

    private Long bytes;

    /** 封面，如 thumbs/p/a001/0000.jpg */
    private String coverPath;

    private String note;

    /**
     * 内容分级（P5.99）：{@code general} 大众 / {@code guidance} 家长指导 / {@code restricted} 限制级
     *
     * <p>由 {@code media_album.content_rating} 承载，值域与校验集中在
     * {@link com.qtools.media.support.MediaRating}。相册自己的分级是**整册**属性：
     * 册里的图片不单独存分级，未登录访客按相册这一档判可见性。</p>
     */
    private String contentRating;

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

    public String getDirKey()
    {
        return dirKey;
    }

    public void setDirKey(String dirKey)
    {
        this.dirKey = dirKey;
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

    public String getContentRating()
    {
        return contentRating;
    }

    public void setContentRating(String contentRating)
    {
        this.contentRating = contentRating;
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
