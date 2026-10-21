package com.qtools.app.domain;

import java.time.LocalDateTime;

/**
 * 用户端登录身份（表 app_user_identity）
 *
 * <p>一个账号可绑多条身份：{@code username} / {@code email} / {@code phone}，
 * {@code (type, identifier)} 唯一，任一身份都可作为登录凭证（能否登录看配置开关）。</p>
 *
 * @author qtools
 */
public class AppUserIdentity
{
    public static final String TYPE_USERNAME = "username";

    public static final String TYPE_EMAIL = "email";

    public static final String TYPE_PHONE = "phone";

    private Long id;

    private Long userId;

    private String type;

    private String identifier;

    /** 1 已验证 / 0 未验证（用户名注册即已验证） */
    private Integer verified;

    private LocalDateTime createdAt;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getIdentifier()
    {
        return identifier;
    }

    public void setIdentifier(String identifier)
    {
        this.identifier = identifier;
    }

    public Integer getVerified()
    {
        return verified;
    }

    public void setVerified(Integer verified)
    {
        this.verified = verified;
    }

    public LocalDateTime getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt)
    {
        this.createdAt = createdAt;
    }
}
