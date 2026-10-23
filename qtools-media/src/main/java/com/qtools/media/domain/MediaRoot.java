package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体库根 media_root（多库根，M1）
 *
 * <p>一行 = 一个可配置的根目录。{@code kind} 区分用途：</p>
 * <ul>
 *   <li>{@code root}：媒体根，{@code media_video.root_id} / {@code media_photo.root_id} 指向它；</li>
 *   <li>{@code thumbs}：缩略图根；{@code trash}：回收站根。</li>
 * </ul>
 *
 * <p>{@code root_id} 为 NULL 的存量行一律走配置 {@code qtools.media.rootPath}，因此老库不迁移也能用。
 * 建表语句见 {@code doc/media_portability.sql}，索引补强见 {@code doc/media_multiroot.sql}。</p>
 *
 * @author qtools
 */
public class MediaRoot
{
    /** 主键：media_root.id，就是 media_video/media_photo 的 root_id */
    private Integer id;

    /** 显示名，如「主库」「备份盘」 */
    private String name;

    /** 根目录绝对路径（换盘时只改这里） */
    private String path;

    /** root=媒体根 / thumbs=缩略图根 / trash=回收站根 */
    private String kind;

    /** 备注 */
    private String note;

    /** 创建时间 */
    private Date createdAt;

    public Integer getId()
    {
        return id;
    }

    public void setId(Integer id)
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

    public String getPath()
    {
        return path;
    }

    public void setPath(String path)
    {
        this.path = path;
    }

    public String getKind()
    {
        return kind;
    }

    public void setKind(String kind)
    {
        this.kind = kind;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public Date getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt)
    {
        this.createdAt = createdAt;
    }
}
