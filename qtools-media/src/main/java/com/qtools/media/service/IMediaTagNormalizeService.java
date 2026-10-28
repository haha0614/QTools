package com.qtools.media.service;

import java.util.Map;

/**
 * 媒体库「标签原子化」迁移服务（P6.03）
 *
 * <p>实现不自己写 SQL：所有迁移逻辑都在 {@code doc/media-import/normalize-tags.js} 里，
 * 与命令行跑的是同一个脚本、同一份映射表（{@code doc/media-import/tag-map.json}）。
 * 理由和后端「扫描 / 缩略图」按钮一致 —— 迁移规则一旦在 Java 与脚本里各写一份，
 * 命令行验证过的结果就与管理端点出来的结果不是同一件事，出问题也无从对照。</p>
 *
 * <p>本服务只做三件事：拼白名单命令行、跑子进程、把脚本输出的统计 JSON 原样回给前端。</p>
 *
 * @author qtools
 */
public interface IMediaTagNormalizeService
{
    /**
     * 跑一次标签规范化
     *
     * @param dryRun {@code true} = 只统计不写库（管理端预览）；{@code false} = 先备份再写库
     * @return 脚本输出的统计（{@code before} / {@code changes} / {@code oldTags} / {@code atomSet} /
     *         {@code facetGroups} / {@code after}）+ {@code stdout} + {@code exitCode} + {@code mode}
     */
    Map<String, Object> normalize(boolean dryRun);
}
