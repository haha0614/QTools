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
}
