package com.qtools.media.service.impl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaStatsMapper;
import com.qtools.media.service.IMediaStatsService;

/**
 * 媒体统计 业务层实现
 *
 * <p>概览字段与 SQL 的对应：
 * <pre>
 * totalEvents      select count(*) from media_event
 * todayEvents      select count(*) from media_event where create_time 落在数据库 curdate()
 * playCount        select count(*) from media_event where event_type='play'
 * photoClickCount  ... event_type='photo_click'
 * pageViewCount    ... event_type='page_view'
 * totalPlayMs      select coalesce(sum(value_num),0) ... event_type='play_end'
 * favCount         select count(*) from media_favorite（实时收藏行数，不是 fav 事件数）
 * trendDays        常量 7
 * dailyEvents      media_stats_daily 近 7 天按天 sum(cnt)，缺失日期在 Java 里补 0
 * topVideos        media_event 按 'play' 次数倒序 top10，标题左联 media_video.title（可为 null）
 * topPhotos        media_event 按 'photo_click' 次数倒序 top10，key = albumId:seq
 * uvToday          select count(distinct owner_key) ... 今天，排除 null 与空串
 * uvTotal          select count(distinct owner_key) ... 全部，排除 null 与空串
 * playEndCount     select count(*) ... event_type='play_end'（完播率分母）
 * completedPlayCount ... event_type='play_end' and progress>=90（完播率分子）
 * completionRate   Java 计算：completedPlayCount / playEndCount * 100（整数，分母为 0 给 0）
 * </pre></p>
 *
 * @author qtools
 */
@Service
public class MediaStatsServiceImpl implements IMediaStatsService
{
    private static final Logger log = LoggerFactory.getLogger(MediaStatsServiceImpl.class);

    private static final String DAY_PATTERN = "yyyy-MM-dd";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern(DAY_PATTERN);

    /** 趋势天数（前端按 7 天画图） */
    private static final int TREND_DAYS = 7;

    /** 榜单条数 */
    private static final int TOP_LIMIT = 10;

    /** 完播阈值（progress 百分比）：>= 90 视为完播，与 SQL 里的字面量保持一致 */
    private static final int COMPLETE_PROGRESS = 90;

    /** 单目标统计的默认 / 合法天数区间 */
    private static final int DAYS_DEFAULT = 7;

    private static final int DAYS_MIN = 1;

    private static final int DAYS_MAX = 30;

    /** 目标类型与"播放次数"口径的映射：album_view / photo_click / play */
    private static final String TARGET_VIDEO = "video";

    private static final String TARGET_PHOTO = "photo";

    private static final String TARGET_ALBUM = "album";

    private static final String EVENT_PLAY = "play";

    private static final String EVENT_PLAY_END = "play_end";

    private static final String EVENT_PHOTO_CLICK = "photo_click";

    private static final String EVENT_ALBUM_VIEW = "album_view";

    @Autowired
    private MediaStatsMapper mediaStatsMapper;

    @Override
    public Map<String, Object> overview()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEvents", mediaStatsMapper.countTotalEvents());
        out.put("todayEvents", mediaStatsMapper.countTodayEvents());
        out.put("playCount", mediaStatsMapper.countByEventType("play"));
        out.put("photoClickCount", mediaStatsMapper.countByEventType("photo_click"));
        out.put("pageViewCount", mediaStatsMapper.countByEventType("page_view"));
        out.put("totalPlayMs", mediaStatsMapper.sumValueByEventType("play_end"));
        out.put("favCount", mediaStatsMapper.countFavorites());
        out.put("trendDays", TREND_DAYS);
        out.put("dailyEvents", dailyEventNodes());
        out.put("topVideos", mediaStatsMapper.selectTopVideos(TOP_LIMIT));
        out.put("topPhotos", mediaStatsMapper.selectTopPhotos(TOP_LIMIT));
        // M5 新增字段：原有字段一个不动，只在末尾追加
        long playEnd = mediaStatsMapper.countPlayEnd();
        long completed = mediaStatsMapper.countCompletedPlay(COMPLETE_PROGRESS);
        out.put("uvToday", mediaStatsMapper.countUvToday());
        out.put("uvTotal", mediaStatsMapper.countUvTotal());
        out.put("playEndCount", playEnd);
        out.put("completedPlayCount", completed);
        out.put("completionRate", percent(completed, playEnd));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuild(String date)
    {
        List<String> dates = new ArrayList<>();
        if (StringUtils.isEmpty(date) || date.trim().isEmpty())
        {
            // 未指定日期：重算昨天 + 今天（跨零点后补前一天用）
            LocalDate today = LocalDate.now();
            dates.add(today.minusDays(1).format(DAY_FORMAT));
            dates.add(today.format(DAY_FORMAT));
        }
        else
        {
            dates.add(parseDay(date));
        }
        int rows = 0;
        for (String day : dates)
        {
            // 先删后插：同一日期重复执行结果不变（幂等），也顺手清掉历史脏汇总
            mediaStatsMapper.deleteDailyByDate(day);
            rows += mediaStatsMapper.rebuildDailyByDate(day);
        }
        log.info("媒体统计按天汇总重建完成 dates={} rows={}", dates, rows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", dates);
        out.put("rows", rows);
        return out;
    }

    /**
     * 近 TREND_DAYS 天连续日期的趋势点，无数据补 0。
     *
     * <p>SQL 只返回有数据的天，这里以 JVM 当天为锚点倒推 7 天补齐并保证升序，
     * 前端拿到的一定是定长数组（跨天/无数据都不会缺列）。</p>
     */
    private List<Map<String, Object>> dailyEventNodes()
    {
        return dailyNodes(mediaStatsMapper.selectDailyEvents(TREND_DAYS - 1), TREND_DAYS);
    }

    /**
     * 把"只含有效数据天"的 SQL 结果补成连续 span 天的升序数组，缺失日期 count=0。
     *
     * <p>锚点用 JVM 当天，与 SQL 的 date_sub(curdate(), ...) 一致（应用与库同机同时区）。</p>
     */
    private static List<Map<String, Object>> dailyNodes(List<Map<String, Object>> rows, int span)
    {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            counted.put(dayKey(row.get("stat_date")), asLong(row.get("cnt")));
        }
        List<Map<String, Object>> nodes = new ArrayList<>(span);
        LocalDate today = LocalDate.now();
        for (int i = span - 1; i >= 0; i--)
        {
            String day = today.minusDays(i).format(DAY_FORMAT);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("date", day);
            node.put("count", counted.getOrDefault(day, 0L));
            nodes.add(node);
        }
        return nodes;
    }

    /** DATE 列在 map 结果里可能是 java.sql.Date / LocalDate / String，统一成 yyyy-MM-dd */
    private static String dayKey(Object value)
    {
        if (value instanceof java.sql.Date sqlDate)
        {
            return sqlDate.toLocalDate().format(DAY_FORMAT);
        }
        if (value instanceof LocalDate localDate)
        {
            return localDate.format(DAY_FORMAT);
        }
        if (value instanceof java.util.Date date)
        {
            return new java.sql.Date(date.getTime()).toLocalDate().format(DAY_FORMAT);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** sum()/count() 经 JDBC 可能回 BigDecimal/BigInteger，统一取 long */
    private static long asLong(Object value)
    {
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        if (value == null)
        {
            return 0L;
        }
        try
        {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    @Override
    public Map<String, Object> target(String targetType, String targetKey, Integer days)
    {
        String type = normalizeTargetType(targetType);
        String key = targetKey == null ? "" : targetKey.trim();
        int span = normalizeDays(days);
        String eventType = countEventType(type);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetType", type);
        out.put("targetKey", key);
        // 展示名：video 用视频标题；photo 用「相册名 / 文件名」；album 用相册名
        out.put("title", targetTitle(type, key));
        out.put("playCount", mediaStatsMapper.countTargetEvents(type, key, eventType));
        // play_end / 完播只有 video 有意义，其它类型恒 0（相册与图片没有播放结束事件）
        long playEnd = TARGET_VIDEO.equals(type) ? mediaStatsMapper.countTargetPlayEnd(type, key) : 0L;
        long completed = TARGET_VIDEO.equals(type)
                ? mediaStatsMapper.countTargetCompleted(type, key, COMPLETE_PROGRESS) : 0L;
        out.put("playEndCount", playEnd);
        out.put("completedCount", completed);
        out.put("completionRate", percent(completed, playEnd));
        out.put("totalPlayMs", mediaStatsMapper.sumTargetPlayMs(type, key));
        out.put("daily", dailyNodes(mediaStatsMapper.selectTargetDaily(type, key, eventType, span - 1), span));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String before)
    {
        String day = parseDay(before);
        // 归档表兜底创建：DDL 在 MySQL 里会隐式提交，所以放在两条 DML 之前，
        // 不影响"搬走 + 删除"这两条语句同成同败（它们仍在同一个事务里）。
        mediaStatsMapper.createArchiveTable();
        int moved = mediaStatsMapper.archiveEvents(day);
        mediaStatsMapper.deleteArchivedEvents(day);
        log.info("媒体明细归档完成 before={} moved={}", day, moved);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved);
        return out;
    }

    /** 单目标展示名：video→视频标题，photo→「相册名 / 文件名」，album→相册名；查不到给 null 由前端回退 key */
    private String targetTitle(String type, String key)
    {
        if (TARGET_VIDEO.equals(type))
        {
            return mediaStatsMapper.selectVideoTitle(key);
        }
        if (TARGET_PHOTO.equals(type))
        {
            return mediaStatsMapper.selectPhotoTitle(key);
        }
        if (TARGET_ALBUM.equals(type))
        {
            return mediaStatsMapper.selectAlbumTitle(key);
        }
        return null;
    }
    /** 单目标的"播放次数"口径：video=play / photo=photo_click / album=album_view */
    private static String countEventType(String targetType)
    {
        switch (targetType)
        {
            case TARGET_PHOTO:
                return EVENT_PHOTO_CLICK;
            case TARGET_ALBUM:
                return EVENT_ALBUM_VIEW;
            default:
                return EVENT_PLAY;
        }
    }

    private static String normalizeTargetType(String targetType)
    {
        String type = targetType == null ? "" : targetType.trim();
        if (TARGET_VIDEO.equals(type) || TARGET_PHOTO.equals(type) || TARGET_ALBUM.equals(type))
        {
            return type;
        }
        // 控制器已先校验并回 AjaxResult.error，这里只是服务层的兜底
        throw MediaException.badRequest("targetType 不合法");
    }

    /** days 夹取到 [1,30]，null 用默认 7 */
    private static int normalizeDays(Integer days)
    {
        if (days == null)
        {
            return DAYS_DEFAULT;
        }
        if (days < DAYS_MIN)
        {
            return DAYS_MIN;
        }
        return Math.min(days, DAYS_MAX);
    }

    /** 整数百分比（整除截断）：分母为 0 时给 0 */
    private static int percent(long part, long total)
    {
        if (total <= 0L)
        {
            return 0;
        }
        return (int) Math.min(100L, part * 100L / total);
    }

    private static String parseDay(String date)
    {
        try
        {
            return LocalDate.parse(date.trim(), DAY_FORMAT).format(DAY_FORMAT);
        }
        catch (DateTimeParseException e)
        {
            throw MediaException.badRequest("date 必须是 yyyy-MM-dd");
        }
    }
}
