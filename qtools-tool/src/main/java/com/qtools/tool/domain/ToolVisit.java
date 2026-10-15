package com.qtools.tool.domain;

import com.qtools.common.core.domain.BaseEntity;

/**
 * 工具访问埋点对象 tool_visit
 *
 * <p>仅用于写入埋点记录（V1 不提供查询接口），创建时间复用 {@link BaseEntity#getCreateTime()}。</p>
 *
 * @author qtools
 */
public class ToolVisit extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 访问记录ID */
    private Long id;

    /** 工具ID（对应 tool_info.id，允许前端兜底 id 造成的脏数据） */
    private Long toolId;

    /** 访问者IP */
    private String ip;

    /** 浏览器 User-Agent（最长 512 字符） */
    private String userAgent;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getToolId()
    {
        return toolId;
    }

    public void setToolId(Long toolId)
    {
        this.toolId = toolId;
    }

    public String getIp()
    {
        return ip;
    }

    public void setIp(String ip)
    {
        this.ip = ip;
    }

    public String getUserAgent()
    {
        return userAgent;
    }

    public void setUserAgent(String userAgent)
    {
        this.userAgent = userAgent;
    }

    @Override
    public String toString()
    {
        return "ToolVisit [id=" + id + ", toolId=" + toolId + ", ip=" + ip + ", userAgent=" + userAgent + "]";
    }
}
