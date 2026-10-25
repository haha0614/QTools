package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体站播放列表 media_playlist（P5-B）
 *
 * <p>{@code ownerKey} 为匿名设备身份（见 {@code MediaOwnerService}）：NULL = 全局列表
 * （所有访客可见，只能由管理端清理），非 NULL = 该设备私有。同名同 owner 复用（不是 409），
 * 唯一键 {@code (name, owner_key)} 与 {@code media_saved_filter} 完全同构。</p>
 *
 * <p>条目在 {@link MediaPlaylistItem}：顺序是列表的全部语义，因此单独成表用 {@code seq} 表达，
 * 不用 JSON 列。建表语句见 {@code doc/media_playlist.sql}。</p>
 *
 * @author qtools
 */
public class MediaPlaylist
{
    /** 主键 */
    private Long id;

    /** 名称（同一 owner 下唯一）；1-64 字符 */
    private String name;

    /** 归属匿名设备；NULL = 全局 */
    private String ownerKey;

    /** 备注（管理端可读；站端接口暂不写） */
    private String note;

    /** 条目数（列表查询的投影列，不在表里） */
    private Integer itemCount;

    private Date createTime;

    private Date updateTime;

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

    public String getOwnerKey()
    {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey)
    {
        this.ownerKey = ownerKey;
    }

    public String getNote()
    {
        return note;
    }

    public void setNote(String note)
    {
        this.note = note;
    }

    public Integer getItemCount()
    {
        return itemCount;
    }

    public void setItemCount(Integer itemCount)
    {
        this.itemCount = itemCount;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }

    public Date getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime)
    {
        this.updateTime = updateTime;
    }
}
