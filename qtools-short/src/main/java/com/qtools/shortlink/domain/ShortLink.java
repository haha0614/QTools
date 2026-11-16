package com.qtools.shortlink.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qtools.common.annotation.Excel;
import com.qtools.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;

/**
 * 短链接对象 short_link
 * 
 * @author qing
 * @date 2026-11-05
 */
public class ShortLink extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 短链编码 */
    @Excel(name = "短链编码")
    private String shortCode;

    /** 长链接 */
    @Excel(name = "长链接")
    private String longUrl;

    /** sha256(longUrl),同链去重用 */
    @Excel(name = "sha256(longUrl),同链去重用")
    private String urlHash;

    /** 用户id */
    @Excel(name = "用户id")
    private Long userId;

    /** 访问密码的sha256，不存明文 */
    @Excel(name = "访问密码的sha256，不存明文")
    private String password;

    /**  */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Excel(name = "", width = 30, dateFormat = "yyyy-MM-dd")
    private Date expireAt;

    /** 状态（0-启用，1-禁用） */
    @Excel(name = "状态", readConverterExp = "0=-启用，1-禁用")
    private String status;

    /** 冗余总量（明细见统计表） */
    @Excel(name = "冗余总量", readConverterExp = "明=细见统计表")
    private Long clicks;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setShortCode(String shortCode) 
    {
        this.shortCode = shortCode;
    }

    public String getShortCode() 
    {
        return shortCode;
    }

    public void setLongUrl(String longUrl) 
    {
        this.longUrl = longUrl;
    }

    public String getLongUrl() 
    {
        return longUrl;
    }

    public void setUrlHash(String urlHash) 
    {
        this.urlHash = urlHash;
    }

    public String getUrlHash() 
    {
        return urlHash;
    }

    public void setUserId(Long userId) 
    {
        this.userId = userId;
    }

    public Long getUserId() 
    {
        return userId;
    }

    public void setPassword(String password) 
    {
        this.password = password;
    }

    public String getPassword() 
    {
        return password;
    }

    public void setExpireAt(Date expireAt) 
    {
        this.expireAt = expireAt;
    }

    public Date getExpireAt() 
    {
        return expireAt;
    }

    public void setStatus(String status) 
    {
        this.status = status;
    }

    public String getStatus() 
    {
        return status;
    }

    public void setClicks(Long clicks) 
    {
        this.clicks = clicks;
    }

    public Long getClicks() 
    {
        return clicks;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("shortCode", getShortCode())
            .append("longUrl", getLongUrl())
            .append("urlHash", getUrlHash())
            .append("userId", getUserId())
            .append("password", getPassword())
            .append("expireAt", getExpireAt())
            .append("status", getStatus())
            .append("clicks", getClicks())
            .append("createTime", getCreateTime())
            .toString();
    }
}
