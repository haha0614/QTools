package com.qtools.tool.service;

import java.util.List;
import com.qtools.tool.domain.ToolInfo;

/**
 * 工具信息 业务层
 *
 * @author qtools
 */
public interface IToolInfoService
{
    /**
     * 查询所有正常状态（status = '0'）的工具列表，按 sort 升序
     *
     * @return 工具信息集合
     */
    public List<ToolInfo> selectEnabledToolList();
}
