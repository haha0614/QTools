package com.qtools.app.domain;

import java.time.LocalDateTime;

/**
 * 用户端账号（表 app_user）
 *
 * <p>与 {@code sys_user} 无任何关联：两套身份互不承认。</p>
 *
 * @author qtools
 */
public class AppUser
{
    private Long id;

    private String username;

    private String nickname;

    private String avatar;

    /** BCrypt(cost=10)；仅验证码登录的账号可为空 */
    private String passwordHash;

    /** 0 正常 / 1 停用 */
    private String status;

    private String inviteCode;

    private String registerIp;

    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getNickname()
    {
        return nickname;
    }

    public void setNickname(String nickname)
    {
        this.nickname = nickname;
    }

    public String getAvatar()
    {
        return avatar;
    }

    public void setAvatar(String avatar)
    {
        this.avatar = avatar;
    }

    public String getPasswordHash()
    {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash)
    {
        this.passwordHash = passwordHash;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getInviteCode()
    {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode)
    {
        this.inviteCode = inviteCode;
    }

    public String getRegisterIp()
    {
        return registerIp;
    }

    public void setRegisterIp(String registerIp)
    {
        this.registerIp = registerIp;
    }

    public LocalDateTime getCreatedAt()
    {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt)
    {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLoginAt()
    {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt)
    {
        this.lastLoginAt = lastLoginAt;
    }
}
