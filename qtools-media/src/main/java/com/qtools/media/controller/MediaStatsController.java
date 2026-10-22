package com.qtools.media.controller;

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
 * @author qtools
 */
@RestController
@RequestMapping("/api/media/admin/stats")
public class MediaStatsController
{
    private static final Logger log = LoggerFactory.getLogger(MediaStatsController.class);

    @Autowired
    private IMediaStatsService mediaStatsService;

    /**
     * 事件统计概览
     *
     * @return data 内固定字段：totalEvents / todayEvents / playCount / photoClickCount /
     *         pageViewCount / totalPlayMs / favCount / trendDays / dailyEvents / topVideos / topPhotos
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        return AjaxResult.success(mediaStatsService.overview());
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
}
