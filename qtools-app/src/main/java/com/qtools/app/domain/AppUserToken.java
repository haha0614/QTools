package com.qtools.app.domain;

import java.time.LocalDateTime;

/**
 * 用户端登录会话（表 app_user_token）：JWT 的 jti 落库，登出 / 强制下线靠它失效
 *
 * @author qtools
 */
public class AppUserToken
{
    private Long id;

    private Long userId;

    /** JWT 的 jti（CHAR(36)，唯一） */
    private String tokenId;

    private String userAgent;

    private String ip;

    private LocalDateTime expiresAt;

    /** 1 已撤销 / 0 有效 */
    private Integer revoked;

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

    public String getTokenId()
    {
        return tokenId;
    }

    public void setTokenId(String tokenId)
    {
        this.tokenId = tokenId;
    }

    public String getUserAgent()
    {
        return userAgent;
    }

    public void setUserAgent(String userAgent)
    {
        this.userAgent = userAgent;
    }

    public String getIp()
    {
        return ip;
    }

    public void setIp(String ip)
    {
        this.ip = ip;
    }

    public LocalDateTime getExpiresAt()
    {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt)
    {
        this.expiresAt = expiresAt;
    }

    public Integer getRevoked()
    {
        return revoked;
    }

    public void setRevoked(Integer revoked)
    {
        this.revoked = revoked;
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
