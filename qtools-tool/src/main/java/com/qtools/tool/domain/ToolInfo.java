package com.qtools.tool.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qtools.common.core.domain.BaseEntity;

/**
 * 工具信息对象 tool_info
 *
 * <p>注意：前端（QTools 前台工具列表页）已按 tool_name / tool_desc / tool_route / icon / sort
 * 这 6 个固定字段名做映射，因此这里用 {@link JsonProperty} 显式指定序列化名称，Java 字段保持驼峰。</p>
 *
 * @author qtools
 */
public class ToolInfo extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 工具ID */
    @JsonProperty("id")
    private Long id;

    /** 工具名称 */
    @JsonProperty("tool_name")
    private String toolName;

    /** 工具描述 */
    @JsonProperty("tool_desc")
    private String toolDesc;

    /** 前端访问路由 */
    @JsonProperty("tool_route")
    private String toolRoute;

    /** 图标名称（前端 Element Plus 图标组件名） */
    @JsonProperty("icon")
    private String icon;

    /** 显示顺序（升序） */
    @JsonProperty("sort")
    private Integer sort;

    /** 状态（0正常 1停用） */
    @JsonProperty("status")
    private String status;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getToolName()
    {
        return toolName;
    }

    public void setToolName(String toolName)
    {
        this.toolName = toolName;
    }

    public String getToolDesc()
    {
        return toolDesc;
    }

    public void setToolDesc(String toolDesc)
    {
        this.toolDesc = toolDesc;
    }

    public String getToolRoute()
    {
        return toolRoute;
    }

    public void setToolRoute(String toolRoute)
    {
        this.toolRoute = toolRoute;
    }

    public String getIcon()
    {
        return icon;
    }

    public void setIcon(String icon)
    {
        this.icon = icon;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    @Override
    public String toString()
    {
        return "ToolInfo [id=" + id + ", toolName=" + toolName + ", toolDesc=" + toolDesc + ", toolRoute=" + toolRoute
                + ", icon=" + icon + ", sort=" + sort + ", status=" + status + "]";
    }
}
