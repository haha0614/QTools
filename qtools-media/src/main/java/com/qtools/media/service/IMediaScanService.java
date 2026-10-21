package com.qtools.media.service;

import java.util.List;
import java.util.Map;

/**
 * 重建索引（扫描任务） 服务层
 *
 * <p>异步执行、同一时刻只允许 1 个 running；子进程复用 {@code doc/media-import/import.js}。</p>
 *
 * @author qtools
 */
public interface IMediaScanService
{
    /**
     * 触发一次扫描
     *
     * @param type full | thumb（当前仅 full 有实现）
     * @return 任务视图（含 jobId）
     * @throws com.qtools.media.exception.MediaException 409 已有任务在运行
     */
    Map<String, Object> start(String type);

    /** 最近一次任务（无任务时返回 null 字段的空视图） */
    Map<String, Object> latest();

    /** 指定任务（不存在 → 404） */
    Map<String, Object> getJob(long jobId);

    /** 最近 N 个任务 */
    List<Map<String, Object>> recent(int limit);
}
