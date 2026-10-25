package com.qtools.media.service;

import java.util.List;
import java.util.Map;
import com.qtools.media.domain.dto.MediaScanRequest;

/**
 * 重建索引 / 维护类任务 服务层
 *
 * <p>异步执行、同一时刻只允许 1 个 running；子进程按 mode 走
 * {@code doc/media-import/} 下的 scan-media.js / build-thumbs.js / enrich-meta.js / export-bundle.js。</p>
 *
 * @author qtools
 */
public interface IMediaScanService
{
    /**
     * 触发一次扫描（旧入口，等价于 {@code start(type, null, false, null)}）
     *
     * @param type full | thumb
     * @return 任务视图（含 jobId）
     * @throws com.qtools.media.exception.MediaException 409 已有任务在运行
     */
    Map<String, Object> start(String type);

    /**
     * 触发一次扫描 / 维护类任务
     *
     * <p>mode 取值：{@code scan | thumb | sprite | preview | sprite,preview | gc | enrich |
     * backfill-hash | fix-poster | export}；可选参数 albumMode / exclude（仅 scan）、noFresh / force
     * （仅 thumb 系）、limit（仅 thumb 系 / enrich）。所有子进程参数由服务层按白名单拼装。</p>
     *
     * @param request 请求参数（type 是旧参数：mode 为空时兜底 full → scan、thumb → thumb）
     * @return 任务视图（含 jobId；mode=export 时另含 exportDir）
     * @throws com.qtools.media.exception.MediaException 400 参数非法 / 409 已有任务在运行
     */
    Map<String, Object> start(MediaScanRequest request);

    /** 最近一次任务（无任务时返回 null 字段的空视图） */
    Map<String, Object> latest();

    /** 指定任务（不存在 → 404） */
    Map<String, Object> getJob(long jobId);

    /** 最近 N 个任务 */
    List<Map<String, Object>> recent(int limit);
}
