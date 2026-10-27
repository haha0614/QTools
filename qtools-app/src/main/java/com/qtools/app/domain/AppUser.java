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

    /** 审核状态：pending 待审核 / approved 已通过 / rejected 未通过（P5.79 起取代旧的 0 正常 / 1 停用） */
    private String status;

    /** 审核备注（拒绝原因等） */
    private String reviewNote;

    /** 最近一次审核时间 */
    private LocalDateTime reviewedAt;

    /** 审核人（sys_user.user_id） */
    private Long reviewedBy;

    private String inviteCode;

    private String registerIp;

    private LocalDateTime createdAt;

    private LocalDateTime lastLoginAt;

    /** 是否封禁：0/null 正常 / true 已封禁（P5.81，独立于审核状态 status） */
    private Boolean banned;

    /** 封禁原因（管理员填写，被拒时回显） */
    private String bannedReason;

    /** 封禁时间（解封时清空） */
    private LocalDateTime bannedAt;

    /** 最近一次管理端写操作时间（资料 / 密码 / 封禁 / 审核，P5.81） */
    private LocalDateTime updatedAt;

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

    public String getReviewNote()
    {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote)
    {
        this.reviewNote = reviewNote;
    }

    public LocalDateTime getReviewedAt()
    {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt)
    {
        this.reviewedAt = reviewedAt;
    }

    public Long getReviewedBy()
    {
        return reviewedBy;
    }

    public void setReviewedBy(Long reviewedBy)
    {
        this.reviewedBy = reviewedBy;
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

    public Boolean getBanned()
    {
        return banned;
    }

    public void setBanned(Boolean banned)
    {
        this.banned = banned;
    }

    /** 是否处于封禁态（null 视为未封禁：迁移前的老行没有这一列的值） */
    public boolean isBanned()
    {
        return Boolean.TRUE.equals(banned);
    }

    public String getBannedReason()
    {
        return bannedReason;
    }

    public void setBannedReason(String bannedReason)
    {
        this.bannedReason = bannedReason;
    }

    public LocalDateTime getBannedAt()
    {
        return bannedAt;
    }

    public void setBannedAt(LocalDateTime bannedAt)
    {
        this.bannedAt = bannedAt;
    }

    public LocalDateTime getUpdatedAt()
    {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt)
    {
        this.updatedAt = updatedAt;
    }
}
