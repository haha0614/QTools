package com.qtools.tool.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 工具站数据看板统计 Mapper。
 *
 * <p>只做聚合查询，返回 Map 以避免为几个标量统计再定义一堆 VO；
 * 列别名（statDate / cnt / toolName / toolRoute / visitCount）即前端拿到的 key。</p>
 *
 * @author qtools
 */
public interface ToolStatsMapper
{
    /** 累计访问量（tool_visit 总行数） */
    Long selectTotalVisits();

    /** 今日访问量 */
    Long selectTodayVisits();

    /** 工具总数 */
    Long selectTotalTools();

    /** 启用中的工具数（status='0'，即前台可见） */
    Long selectEnabledTools();

    /**
     * 按天统计最近若干天的访问量（只返回有数据的日期，缺失日期由调用方补 0）。
     *
     * @param days 往前推的天数（0 表示只统计今天）
     */
    List<Map<String, Object>> selectDailyVisits(@Param("days") int days);

    /** 工具访问排行（含从未被访问的工具，count 为 0） */
    List<Map<String, Object>> selectTopTools(@Param("limit") int limit);
}
