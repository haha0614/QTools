package com.qtools.tool.service;

import java.util.List;
import com.qtools.tool.domain.ToolInfo;

/**
 * 工具信息Service接口
 * 
 * @author qing
 * @date 2026-10-21
 */
public interface IToolAdminService
{
    /**
     * 查询工具信息
     * 
     * @param id 工具信息主键
     * @return 工具信息
     */
    public ToolInfo selectToolInfoById(Long id);

    /**
     * 查询工具信息列表
     * 
     * @param ToolInfo 工具信息
     * @return 工具信息集合
     */
    public List<ToolInfo> selectToolInfoList(ToolInfo ToolInfo);

    /**
     * 新增工具信息
     * 
     * @param ToolInfo 工具信息
     * @return 结果
     */
    public int insertToolInfo(ToolInfo ToolInfo);

    /**
     * 修改工具信息
     * 
     * @param ToolInfo 工具信息
     * @return 结果
     */
    public int updateToolInfo(ToolInfo ToolInfo);

    /**
     * 批量删除工具信息
     * 
     * @param ids 需要删除的工具信息主键集合
     * @return 结果
     */
    public int deleteToolInfoByIds(Long[] ids);

    /**
     * 删除工具信息信息
     * 
     * @param id 工具信息主键
     * @return 结果
     */
    public int deleteToolInfoById(Long id);
}
