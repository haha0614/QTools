package com.qtools.media.service;

import java.util.Map;
import com.qtools.media.domain.dto.MediaScanIngestRequest;

/**
 * 扫描入库（管理端「扫描入库」页的执行器）
 *
 * <p>与 {@link IMediaScanService}（按 mode 跑维护任务，任务落 {@code media_scan_job}）是两条路：
 * 这里<b>只做一件事</b>——把某个库根目录遍历一遍写进库，并且需要「实时日志 + 协作式取消」，
 * 所以任务状态只存在内存（{@code ConcurrentHashMap}，最多留最近 10 个），日志逐行进环形缓冲
 * （2000 行）而不是任务结束后一次性回写。</p>
 *
 * <p>两边共用 {@code MediaScanGate} 的同一把锁：同一时刻只允许一个 node 子进程。</p>
 *
 * @author qtools
 */
public interface IMediaScanIngestService
{
    /**
     * 启动一次扫描入库（异步）
     *
     * @param request 库根 + 开关
     * @return {@code {jobId, command, startedAt, status, rootId, rootName, rootPath, dryRun, withVideo, withPhoto, limit, force}}
     * @throws com.qtools.media.exception.MediaException 400 参数非法 / 404 库根不存在 / 409 已有任务在运行
     */
    Map<String, Object> start(MediaScanIngestRequest request);

    /** 当前正在跑的任务（没有则 null） */
    Map<String, Object> active();

    /**
     * 指定任务的状态与日志尾部
     *
     * @param jobId 任务 id（{@code scn-<n>}）
     * @param tail  最多回多少行日志（null 或 <=0 = 环形缓冲里的全部，上限 2000）
     * @throws com.qtools.media.exception.MediaException 404 任务不存在（进程重启后内存清空也是 404）
     */
    Map<String, Object> getJob(String jobId, Integer tail);

    /**
     * 协作式取消：destroy 子进程，等任务线程把状态落成 cancelled
     *
     * @throws com.qtools.media.exception.MediaException 404 任务不存在 / 409 任务已经结束
     */
    Map<String, Object> cancel(String jobId);

    /**
     * 单步重试（P5.97）：只重跑指定的阶段，成功后自动把后面还没成功的阶段接上
     *
     * <p>为什么允许「指定任意阶段」而不只是失败的那一段：阶段动作全部幂等，管理员看到
     * 「缩略图跳过了 100 条」也会想单独再来一遍。</p>
     *
     * @param jobId    任务 id（{@code scn-<n>}）
     * @param phaseKey 阶段键：scan / hash / meta / thumb / sprite / index
     * @return 任务视图（该阶段已重置为 pending）
     * @throws com.qtools.media.exception.MediaException 404 任务不存在 / 400 阶段不存在或在 dry-run 任务上重试
     *         / 409 任务正在运行或有别的扫描任务在跑
     */
    Map<String, Object> retryPhase(String jobId, String phaseKey);

    /**
     * 新增库根前的预演（P5.97）：{@code node scan-media.js --root=<path> --dry-run}
     *
     * <p><b>只读</b>：脚本在 dry-run 下不连库、不写库（{@code needDb = !(dryRun||dumpSql)}），本方法也不写
     * {@code media_root}。因此它接受一个<b>不在库里</b>的路径——这是「建根之前先看清下面有多少东西」的
     * 唯一办法；真实扫描仍然只认 {@code media_root.path}。</p>
     *
     * @param path 服务器上的绝对路径（存在、是目录、可读；含 {@code ..} 一律 400）
     * @return {@code {path, videos, albums, photos, files, bytes, ignored:{ext:count}, ignoredTotal,
     *         dryRunMs, estimateSeconds, walkTruncated, command}}
     * @throws com.qtools.media.exception.MediaException 400 路径非法 / 不存在 / 无权限 / 脚本失败
     */
    Map<String, Object> preview(String path);
}
