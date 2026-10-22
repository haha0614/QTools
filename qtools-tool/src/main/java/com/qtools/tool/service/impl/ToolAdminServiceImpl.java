package com.qtools.tool.service.impl;

import java.util.List;
import com.qtools.common.utils.DateUtils;
import com.qtools.tool.mapper.ToolAdminMapper;
import com.qtools.tool.service.IToolAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.tool.mapper.ToolInfoMapper;
import com.qtools.tool.domain.ToolInfo;
import com.qtools.tool.service.IToolInfoService;

/**
 * 工具信息Service业务层处理
 * 
 * @author qing
 * @date 2026-10-21
 */
@Service
public class ToolAdminServiceImpl implements IToolAdminService
{
    @Autowired
    private ToolAdminMapper toolAdminMapper;

    /**
     * 查询工具信息
     * 
     * @param id 工具信息主键
     * @return 工具信息
     */
    @Override
    public ToolInfo selectToolInfoById(Long id)
    {
        return toolAdminMapper.selectToolInfoById(id);
    }

    /**
     * 查询工具信息列表
     * 
     * @param ToolInfo 工具信息
     * @return 工具信息
     */
    @Override
    public List<ToolInfo> selectToolInfoList(ToolInfo ToolInfo)
    {
        return toolAdminMapper.selectToolInfoList(ToolInfo);
    }

    /**
     * 新增工具信息
     * 
     * @param ToolInfo 工具信息
     * @return 结果
     */
    @Override
    public int insertToolInfo(ToolInfo ToolInfo)
    {
        ToolInfo.setCreateTime(DateUtils.getNowDate());
        return toolAdminMapper.insertToolInfo(ToolInfo);
    }

    /**
     * 修改工具信息
     * 
     * @param ToolInfo 工具信息
     * @return 结果
     */
    @Override
    public int updateToolInfo(ToolInfo ToolInfo)
    {
        ToolInfo.setUpdateTime(DateUtils.getNowDate());
        return toolAdminMapper.updateToolInfo(ToolInfo);
    }

    /**
     * 批量删除工具信息
     * 
     * @param ids 需要删除的工具信息主键
     * @return 结果
     */
    @Override
    public int deleteToolInfoByIds(Long[] ids)
    {
        return toolAdminMapper.deleteToolInfoByIds(ids);
    }

    /**
     * 删除工具信息信息
     * 
     * @param id 工具信息主键
     * @return 结果
     */
    @Override
    public int deleteToolInfoById(Long id)
    {
        return toolAdminMapper.deleteToolInfoById(id);
    }
}
