package com.qtools.app.domain;

import java.time.LocalDateTime;

/**
 * 邀请码（表 app_invite_code）：{@code mode=invite} 注册时使用
 *
 * @author qtools
 */
public class AppInviteCode
{
    private Long id;

    private String code;

    private Integer maxUses;

    private Integer usedCount;

    /** 空 = 永久有效 */
    private LocalDateTime expiresAt;

    private String createdBy;

    private LocalDateTime createdAt;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public Integer getMaxUses()
    {
        return maxUses;
    }

    public void setMaxUses(Integer maxUses)
    {
        this.maxUses = maxUses;
    }

    public Integer getUsedCount()
    {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount)
    {
        this.usedCount = usedCount;
    }

    public LocalDateTime getExpiresAt()
    {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt)
    {
        this.expiresAt = expiresAt;
    }

    public String getCreatedBy()
    {
        return createdBy;
    }

    public void setCreatedBy(String createdBy)
    {
        this.createdBy = createdBy;
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
