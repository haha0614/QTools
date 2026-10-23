package com.qtools.media.controller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.media.service.IMediaStatsService;

/**
 * 管理端媒体统计接口（前缀 /api/media/admin/stats）
 *
 * <p>与用户端不同：本控制器<b>不加</b> {@code @Anonymous}，沿用 Security 的
 * {@code /api/media/admin/**} 需要认证规则，并在每个方法上叠加
 * {@code @PreAuthorize("@ss.hasPermi('media:admin:list')")}。</p>
 *
 * <p>参数校验在本控制器内直接回 {@link AjaxResult#error(String)}，<b>不抛</b> {@code MediaException}：
 * 本模块的 {@code MediaExceptionHandler} 优先级高于框架的 GlobalExceptionHandler，会把异常改写成
 * {@code {"error":"..."}}，而管理端沿用的是 RuoYi 的 AjaxResult 约定。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/media/admin/stats")
public class MediaStatsController
{
    private static final Logger log = LoggerFactory.getLogger(MediaStatsController.class);

    private static final String DAY_PATTERN = "yyyy-MM-dd";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern(DAY_PATTERN);

    /** 单目标统计允许的目标类型 */
    private static final Set<String> TARGET_TYPES = Set.of("video", "photo", "album");

    @Autowired
    private IMediaStatsService mediaStatsService;

    /**
     * 事件统计概览
     *
     * @return data 内固定字段：totalEvents / todayEvents / playCount / photoClickCount /
     *         pageViewCount / totalPlayMs / favCount / trendDays / dailyEvents / topVideos / topPhotos，
     *         以及 M5 追加的 uvToday / uvTotal / playEndCount / completedPlayCount / completionRate
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        return AjaxResult.success(mediaStatsService.overview());
    }

    /**
     * 单目标统计（"点开某个视频看曲线"）
     *
     * @param targetType video / photo / album，其它值回 error
     * @param targetKey  video=media_video.id；photo=albumId:seq；album=media_album.id
     * @param days       近 N 天，夹取 1-30，缺省 7
     * @return data 内 {targetType,targetKey,title,playCount,playEndCount,completedCount,
     *         completionRate,totalPlayMs,daily:[{date,count}]}
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/target")
    public AjaxResult target(@RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetKey", required = false) String targetKey,
            @RequestParam(value = "days", required = false) Integer days)
    {
        String type = trim(targetType);
        if (!TARGET_TYPES.contains(type))
        {
            return AjaxResult.error("targetType 不合法");
        }
        String key = trim(targetKey);
        if (key.isEmpty())
        {
            return AjaxResult.error("targetKey 不能为空");
        }
        return AjaxResult.success(mediaStatsService.target(type, key, days));
    }

    /**
     * 明细归档：把 create_time 早于 before 的事件搬进 media_event_archive（只保留、不提供查询接口）
     *
     * @param before yyyy-MM-dd，必填；格式非法回 error
     * @return data 内 {moved:搬走行数}
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @PostMapping("/archive")
    public AjaxResult archive(@RequestParam(value = "before", required = false) String before)
    {
        String day = normalizeDay(before);
        if (day == null)
        {
            return AjaxResult.error("before 必须是 yyyy-MM-dd");
        }
        log.info("管理端归档媒体明细 before={}", day);
        return AjaxResult.success(mediaStatsService.archive(day));
    }

    /**
     * 从明细重算指定日期的按天汇总（先删后插，幂等）
     *
     * @param date yyyy-MM-dd，缺省为昨天 + 今天
     * @return data 内 {dates:[...], rows:写入行数合计}
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @PostMapping("/rebuild")
    public AjaxResult rebuild(@RequestParam(value = "date", required = false) String date)
    {
        log.info("管理端重建媒体按天汇总 date={}", date);
        return AjaxResult.success(mediaStatsService.rebuild(date));
    }

    /** null 安全的去空白；null 视为空串 */
    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }

    /** 校验并归一化 yyyy-MM-dd；非法返回 null（由调用方回 AjaxResult.error） */
    private static String normalizeDay(String value)
    {
        String day = trim(value);
        if (day.isEmpty())
        {
            return null;
        }
        try
        {
            return LocalDate.parse(day, DAY_FORMAT).format(DAY_FORMAT);
        }
        catch (DateTimeParseException e)
        {
            return null;
        }
    }
}
