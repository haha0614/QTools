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
     * 触发一次扫描（旧入口，等价于 {@code start(type, null, false, null)}）
     *
     * @param type full | thumb
     * @return 任务视图（含 jobId）
     * @throws com.qtools.media.exception.MediaException 409 已有任务在运行
     */
    Map<String, Object> start(String type);

    /**
     * 触发一次扫描 / 生成类任务
     *
     * @param type      旧参数：mode 为空时兜底（full → scan，thumb → thumb）
     * @param mode      {@code scan | thumb | sprite | preview | gc | sprite,preview}，可为空
     * @param gcConfirm mode=gc 时必须为 true，否则 400（真删派生文件）
     * @param limit     只处理前 n 条（仅 thumb / sprite / preview 生效，0 或 null = 不限）
     * @return 任务视图（含 jobId）
     * @throws com.qtools.media.exception.MediaException 400 参数非法 / 409 已有任务在运行
     */
    Map<String, Object> start(String type, String mode, boolean gcConfirm, Integer limit);

    /** 最近一次任务（无任务时返回 null 字段的空视图） */
    Map<String, Object> latest();

    /** 指定任务（不存在 → 404） */
    Map<String, Object> getJob(long jobId);

    /** 最近 N 个任务 */
    List<Map<String, Object>> recent(int limit);
}
