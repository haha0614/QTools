package com.qtools.media.service;

import java.util.Map;

/**
 * 媒体统计 业务层（管理端）
 *
 * @author qtools
 */
public interface IMediaStatsService
{
    /** 概览：总量 / 今日 / 分类计数 / 近 7 天趋势 / 榜单（字段见实现类注释，前端已按此契约开发） */
    Map<String, Object> overview();

    /** 从 media_event 重算按天汇总；date 为空时重算昨天 + 今天。返回 {dates:[...], rows:总行数} */
    Map<String, Object> rebuild(String date);

    /**
     * 单目标统计（"点开某个视频看曲线"）：targetType=video|photo|album，days 夹取 1-30（默认 7）。
     *
     * <p>返回 {targetType, targetKey, title, playCount, playEndCount, completedCount,
     * completionRate, totalPlayMs, daily:[{date,count}]}，daily 为连续 N 天、升序、无数据补 0。</p>
     */
    Map<String, Object> target(String targetType, String targetKey, Integer days);

    /**
     * 明细归档：把 create_time 早于 before(yyyy-MM-dd) 的事件搬进 media_event_archive（只保留、不提供查询接口）。
     *
     * <p>两条语句同事务，返回 {moved:搬走行数}；同一 before 重复执行第二次 moved=0（幂等）。</p>
     */
    Map<String, Object> archive(String before);
}
