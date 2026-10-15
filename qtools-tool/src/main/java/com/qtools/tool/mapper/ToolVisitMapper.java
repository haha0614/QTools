package com.qtools.tool.mapper;

import com.qtools.tool.domain.ToolVisit;

/**
 * 工具访问埋点 数据层
 *
 * <p>包名符合 framework 中 @MapperScan("com.qtools.**.mapper")，无需 @Mapper 注解。</p>
 *
 * @author qtools
 */
public interface ToolVisitMapper
{
    /**
     * 新增一条工具访问埋点记录
     *
     * @param toolVisit 访问埋点信息
     * @return 结果
     */
    public int insertToolVisit(ToolVisit toolVisit);
}
