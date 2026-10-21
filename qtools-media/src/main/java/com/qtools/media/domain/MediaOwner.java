package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体所有者（匿名设备） media_owner
 *
 * @author qtools
 */
public class MediaOwner
{
    private Long id;

    /** 匿名设备 token（cookie ms_owner / 请求头 X-Ms-Owner） */
    private String ownerKey;

    /** 昵称（用户端 profile.nick） */
    private String displayName;

    /** 头像 data:image/(png|jpeg|webp);base64,... 原样存；无则 null */
    private String avatar;

    /** 预留：将来绑定用户端账号 app_user.id */
    private Long userId;

    private Date createdAt;

    private Date lastSeen;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getOwnerKey()
    {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey)
    {
        this.ownerKey = ownerKey;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public String getAvatar()
    {
        return avatar;
    }

    public void setAvatar(String avatar)
    {
        this.avatar = avatar;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public Date getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt)
    {
        this.createdAt = createdAt;
    }

    public Date getLastSeen()
    {
        return lastSeen;
    }

    public void setLastSeen(Date lastSeen)
    {
        this.lastSeen = lastSeen;
    }
}
