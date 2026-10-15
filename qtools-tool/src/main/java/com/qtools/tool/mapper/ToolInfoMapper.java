package com.qtools.tool.mapper;

import java.util.List;
import com.qtools.tool.domain.ToolInfo;

/**
 * 工具信息 数据层
 *
 * <p>包名符合 framework 中 @MapperScan("com.qtools.**.mapper")，无需 @Mapper 注解。</p>
 *
 * @author qtools
 */
public interface ToolInfoMapper
{
    /**
     * 查询所有正常状态（status = '0'）的工具列表，按 sort 升序
     *
     * @return 工具信息集合
     */
    public List<ToolInfo> selectEnabledToolList();
}
