package com.qtools.tool.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.tool.domain.ToolInfo;
import com.qtools.tool.mapper.ToolInfoMapper;
import com.qtools.tool.service.IToolInfoService;

/**
 * 工具信息 业务层实现
 *
 * @author qtools
 */
@Service
public class ToolInfoServiceImpl implements IToolInfoService
{
    @Autowired
    private ToolInfoMapper toolInfoMapper;

    /**
     * 查询所有正常状态（status = '0'）的工具列表，按 sort 升序
     *
     * @return 工具信息集合
     */
    @Override
    public List<ToolInfo> selectEnabledToolList()
    {
        return toolInfoMapper.selectEnabledToolList();
    }
}
