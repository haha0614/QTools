package com.qtools.media.domain;

/**
 * 当前请求的匿名所有者上下文（owner_key + 个人资料）
 *
 * @author qtools
 */
public class MediaOwnerSession
{
    /** 匿名设备 key（cookie ms_owner 或请求头 X-Ms-Owner） */
    private final String ownerKey;

    /** 本次请求是否新生成 key（需要回写 Set-Cookie） */
    private final boolean fresh;

    /** 昵称，默认「我」 */
    private String nick;

    /** 头像 data URI，默认 null */
    private String avatar;

    public MediaOwnerSession(String ownerKey, boolean fresh)
    {
        this.ownerKey = ownerKey;
        this.fresh = fresh;
    }

    public String getOwnerKey()
    {
        return ownerKey;
    }

    public boolean isFresh()
    {
        return fresh;
    }

    public String getNick()
    {
        return nick;
    }

    public void setNick(String nick)
    {
        this.nick = nick;
    }

    public String getAvatar()
    {
        return avatar;
    }

    public void setAvatar(String avatar)
    {
        this.avatar = avatar;
    }
}
