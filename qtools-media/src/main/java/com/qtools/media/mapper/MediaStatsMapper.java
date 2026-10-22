package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 媒体统计 数据层（管理端概览 + 按天汇总重建）
 *
 * <p>概览里的累计类指标（事件总数 / 各类事件次数 / 播放总时长）直接扫 media_event；
 * 趋势图走 media_stats_daily（按天预聚合，不足 7 天的由服务层补 0）。</p>
 *
 * @author qtools
 */
public interface MediaStatsMapper
{
    /** media_event 总行数 */
    long countTotalEvents();

    /** 今天（数据库当天）的事件数 */
    long countTodayEvents();

    /** 指定 event_type 的累计次数 */
    long countByEventType(@Param("eventType") String eventType);

    /** 指定 event_type 的 value_num 累计值（无数据为 0） */
    long sumValueByEventType(@Param("eventType") String eventType);

    /** media_favorite 实时行数（收藏数以表为准，不数 fav 事件） */
    long countFavorites();

    /** 近 N 天按天汇总（只返回有数据的天，缺的日期由服务层补 0）；offsetDays=0 表示只取今天 */
    List<Map<String, Object>> selectDailyEvents(@Param("offsetDays") int offsetDays);

    /** 播放次数 top N 视频：key/title/count（title 允许 null） */
    List<Map<String, Object>> selectTopVideos(@Param("limit") int limit);

    /** 图片点击次数 top N：key/count */
    List<Map<String, Object>> selectTopPhotos(@Param("limit") int limit);

    /** 按日期清掉汇总（rebuild 的第一步，保证幂等） */
    int deleteDailyByDate(@Param("statDate") String statDate);

    /** 从 media_event 聚合指定日期写入 media_stats_daily，返回写入行数 */
    int rebuildDailyByDate(@Param("statDate") String statDate);
}
