package com.qtools.tool.controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.tool.mapper.ToolStatsMapper;

/**
 * 工具站数据看板（管理端首页使用）。
 *
 * <p>数据来源：tool_info（工具维度） + tool_visit（访问埋点）。
 * 埋点只记录"哪个工具被打开了一次"，不含用户在工具页面内输入的任何内容。</p>
 *
 * <p>权限沿用后台工具管理的 {@code qtool:admin:list}，无需额外配置菜单权限。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/qtool/stats")
public class ToolStatsController
{
    /** 趋势图展示天数（含今天） */
    private static final int TREND_DAYS = 7;

    /** 排行展示条数 */
    private static final int TOP_LIMIT = 10;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private ToolStatsMapper toolStatsMapper;

    /**
     * 看板总览：总量指标 + 近 7 天访问趋势 + 工具访问排行。
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:list')")
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        Map<String, Object> data = new HashMap<>(8);
        data.put("totalVisits", nvl(toolStatsMapper.selectTotalVisits()));
        data.put("todayVisits", nvl(toolStatsMapper.selectTodayVisits()));
        data.put("totalTools", nvl(toolStatsMapper.selectTotalTools()));
        data.put("enabledTools", nvl(toolStatsMapper.selectEnabledTools()));
        data.put("trendDays", TREND_DAYS);
        // 数据库只返回有访问的日期，这里补齐成连续 N 天，前端折线才不会断点
        data.put("dailyVisits", buildTrend(toolStatsMapper.selectDailyVisits(TREND_DAYS - 1)));
        data.put("topTools", toolStatsMapper.selectTopTools(TOP_LIMIT));
        return AjaxResult.success(data);
    }

    /**
     * 把稀疏的按天统计补齐为连续 TREND_DAYS 天（日期升序，无数据补 0）。
     */
    private List<Map<String, Object>> buildTrend(List<Map<String, Object>> rows)
    {
        Map<String, Long> counted = new HashMap<>();
        if (rows != null)
        {
            for (Map<String, Object> row : rows)
            {
                Object date = row.get("statDate");
                Object cnt = row.get("cnt");
                if (date != null && cnt instanceof Number)
                {
                    counted.put(String.valueOf(date), ((Number) cnt).longValue());
                }
            }
        }
        List<Map<String, Object>> trend = new ArrayList<>(TREND_DAYS);
        LocalDate today = LocalDate.now();
        for (int i = TREND_DAYS - 1; i >= 0; i--)
        {
            String date = today.minusDays(i).format(DATE_FMT);
            Map<String, Object> item = new HashMap<>(2);
            item.put("date", date);
            item.put("count", counted.getOrDefault(date, 0L));
            trend.add(item);
        }
        return trend;
    }

    private static long nvl(Long value)
    {
        return value == null ? 0L : value;
    }
}
