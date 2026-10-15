package com.qtools.tool.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 工具访问埋点新增入参
 *
 * <p>请求体形如：{"toolId": 1}</p>
 *
 * @author qtools
 */
public class ToolVisitAddDTO
{
    /** 工具ID */
    @NotNull(message = "toolId不能为空")
    private Long toolId;

    public Long getToolId()
    {
        return toolId;
    }

    public void setToolId(Long toolId)
    {
        this.toolId = toolId;
    }

    @Override
    public String toString()
    {
        return "ToolVisitAddDTO [toolId=" + toolId + "]";
    }
}
