package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 媒体统计 数据层（管理端概览 + 按天汇总重建 + 单目标统计 + 明细归档）
 *
 * <p>概览里的累计类指标（事件总数 / 各类事件次数 / 播放总时长 / UV / 完播率）直接扫 media_event；
 * 趋势图走 media_stats_daily（按天预聚合，不足 7 天的由服务层补 0）。</p>
 *
 * <p>单目标统计（{@code /target}）故意不走汇总表：单目标明细量小，口径还能直接用 progress 列，
 * 不必给 media_stats_daily 增加维度。</p>
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

    /** 今天（数据库当天）的 distinct owner_key 数（排除 NULL 与空串） */
    long countUvToday();

    /** 累计 distinct owner_key 数（排除 NULL 与空串） */
    long countUvTotal();

    /** event_type='play_end' 的累计次数（完播率分母） */
    long countPlayEnd();

    /** event_type='play_end' 且 progress>=阈值 的累计次数（完播率分子，阈值由调用方给） */
    long countCompletedPlay(@Param("progress") int progress);

    /** 单目标下指定 event_type 的次数（video 用 play / photo 用 photo_click / album 用 album_view） */
    long countTargetEvents(@Param("targetType") String targetType, @Param("targetKey") String targetKey,
            @Param("eventType") String eventType);

    /** 单目标的 play_end 次数 */
    long countTargetPlayEnd(@Param("targetType") String targetType, @Param("targetKey") String targetKey);

    /** 单目标的完播次数（play_end 且 progress>=阈值） */
    long countTargetCompleted(@Param("targetType") String targetType, @Param("targetKey") String targetKey,
            @Param("progress") int progress);

    /** 单目标 play_end 的 value_num 合计（总播放时长 ms） */
    long sumTargetPlayMs(@Param("targetType") String targetType, @Param("targetKey") String targetKey);

    /** 单目标近 N 天按天计数（只返回有数据的天，缺的日期由服务层补 0）；offsetDays=0 表示只取今天 */
    List<Map<String, Object>> selectTargetDaily(@Param("targetType") String targetType,
            @Param("targetKey") String targetKey, @Param("eventType") String eventType,
            @Param("offsetDays") int offsetDays);

    /** 视频标题（仅 video 目标有标题，查不到返回 null） */
    String selectVideoTitle(@Param("videoId") String videoId);

    /** 图片标题：相册名 / 文件名（key 形如 albumId:seq，查不到返回 null） */
    String selectPhotoTitle(@Param("targetKey") String targetKey);

    /** 相册标题（targetType=album 时用，查不到返回 null） */
    String selectAlbumTitle(@Param("albumId") String albumId);

    /** 归档表兜底创建：create table if not exists ... like media_event（幂等 DDL） */
    int createArchiveTable();

    /** 把 create_time 早于 before 的明细搬进归档表，返回搬走行数 */
    int archiveEvents(@Param("before") String before);

    /** 删除已经搬进归档表的明细（与 {@link #archiveEvents} 同事务） */
    int deleteArchivedEvents(@Param("before") String before);
}
