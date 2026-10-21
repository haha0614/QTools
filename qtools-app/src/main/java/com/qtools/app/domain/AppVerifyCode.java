package com.qtools.app.domain;

import java.time.LocalDateTime;

/**
 * 用户端验证码（表 app_user_verify_code）
 *
 * <p>当前阶段只建表、通道关闭（{@code qtools.app.code.enabled=false}）：
 * {@code /api/app/auth/code} 与 {@code /bind/**} 一律 501，M6 接入短信/邮件服务商后启用。</p>
 *
 * @author qtools
 */
public class AppVerifyCode
{
    public static final String CHANNEL_EMAIL = "email";

    public static final String CHANNEL_SMS = "sms";

    public static final String PURPOSE_BIND = "bind";

    private Long id;

    private String channel;

    private String target;

    private String purpose;

    /** 验证码哈希（BCrypt），不存明文 */
    private String codeHash;

    private Integer attempts;

    /** 1 已使用 */
    private Integer used;

    private LocalDateTime expiresAt;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getChannel()
    {
        return channel;
    }

    public void setChannel(String channel)
    {
        this.channel = channel;
    }

    public String getTarget()
    {
        return target;
    }

    public void setTarget(String target)
    {
        this.target = target;
    }

    public String getPurpose()
    {
        return purpose;
    }

    public void setPurpose(String purpose)
    {
        this.purpose = purpose;
    }

    public String getCodeHash()
    {
        return codeHash;
    }

    public void setCodeHash(String codeHash)
    {
        this.codeHash = codeHash;
    }

    public Integer getAttempts()
    {
        return attempts;
    }

    public void setAttempts(Integer attempts)
    {
        this.attempts = attempts;
    }

    public Integer getUsed()
    {
        return used;
    }

    public void setUsed(Integer used)
    {
        this.used = used;
    }

    public LocalDateTime getExpiresAt()
    {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt)
    {
        this.expiresAt = expiresAt;
    }
}
