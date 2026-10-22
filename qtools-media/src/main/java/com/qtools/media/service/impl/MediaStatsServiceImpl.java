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
        Map<String, Long> counted = new LinkedHashMap<>();
        for (Map<String, Object> row : mediaStatsMapper.selectDailyEvents(TREND_DAYS - 1))
        {
            counted.put(dayKey(row.get("stat_date")), asLong(row.get("cnt")));
        }
        List<Map<String, Object>> nodes = new ArrayList<>(TREND_DAYS);
        LocalDate today = LocalDate.now();
        for (int i = TREND_DAYS - 1; i >= 0; i--)
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
