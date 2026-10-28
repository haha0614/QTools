package com.qtools.media.support;

import org.springframework.stereotype.Component;

/**
 * 扫描类子进程的<b>唯一互斥点</b>（管理端「重建索引 / 维护任务」与新「扫描入库」共用）
 *
 * <p>为什么要一个共用 Bean：两类任务都起 {@code node doc/media-import/*.js} 去读同一个库、写同一批
 * {@code media_*} 表，绝不能并行。{@code MediaScanServiceImpl} 原来只用自己类里的 {@code startLock}
 * 挡同 JVM 的并发请求，{@link MediaScanIngestServiceImpl} 是另一个类，各自一把锁等于没锁。
 * 于是把「谁在跑」这件事提到这里：两边都在 {@link #lock} 上加锁再判断，
 * 锁一次、判据一个。</p>
 *
 * <p>{@link #isIngestRunning()} 只反映<b>扫描入库</b>那一侧：维护任务的 running 状态本来就有
 * {@code media_scan_job} 的 running 行兜底（进程重启后也还在），不需要再抄一份到内存。</p>
 *
 * @author qtools
 */
@Component
public class MediaScanGate
{
    /** 跨两个 Starter 的唯一监视器：任何「开一个新的 node 子进程」的入口都必须在它上面同步 */
    public final Object lock = new Object();

    /** 正在跑的扫描入库任务 id（null = 没有）；受 {@link #lock} 保护 */
    private String ingestJobId;

    /**
     * 正在跑的<b>扫描入库流水线</b>任务 id（null = 没有）；受 {@link #lock} 保护
     *
     * <p>P5.97：扫描入库不再是「一个子进程」，而是 scan → 补哈希 → 缩略图 → 索引 的阶段流水线。
     * {@link #ingestJobId} 只在扫描那一段有效（阶段之间会释放，否则后面的阶段自己会被互斥挡住），
     * 于是阶段之间出现一个毫秒级窗口——别人此刻点「重建缩略图」就能插进来，两个子进程同时写库。
     * 所以另记一个「流水线在位」标记：整个流水线期间（含阶段间隙、含单步重试）都算占锁。</p>
     */
    private String pipelineJobId;

    /**
     * 流水线正在「起自己的下一阶段子任务」
     *
     * <p>阶段的子任务本身走 {@code MediaScanService.start(...)}，而那个入口会检查
     * {@link #isPipelineRunning()}——不开口子的话流水线会把自己挡住。调用方必须在
     * {@link #lock} 内 {@code phaseEntering()} → 起子任务 → {@code phaseEntered()}，
     * 于是外部请求在这段内同样进不来（同一把锁），不存在绕过窗口。</p>
     */
    private boolean internalPhase;

    /** 是否有扫描入库任务在跑；调用方一般已经在 {@link #lock} 内 */
    public boolean isIngestRunning()
    {
        return ingestJobId != null;
    }

    /** 正在跑的扫描入库任务 id（没有则 null）；仅用于错误文案 */
    public String getIngestJobId()
    {
        return ingestJobId;
    }

    /** 登记：调用方必须在 {@link #lock} 内、且已确认 {@link #isIngestRunning()} 为 false */
    public void ingestStarted(String jobId)
    {
        ingestJobId = jobId;
    }

    /** 注销：只清自己那次（任务被拒 / 结束后调用，重复调用幂等） */
    public void ingestFinished(String jobId)
    {
        if (jobId != null && jobId.equals(ingestJobId))
        {
            ingestJobId = null;
        }
    }

    /** 是否有扫描入库流水线在位（含阶段间隙 / 单步重试）；调用方一般已经在 {@link #lock} 内 */
    public boolean isPipelineRunning()
    {
        return pipelineJobId != null && !internalPhase;
    }

    /** 流水线要起自己的下一阶段子任务：调用方必须已在 {@link #lock} 内 */
    public void phaseEntering()
    {
        internalPhase = true;
    }

    /** 子任务已经起来了（任务行已在库里，后续由 running 计数挡住别人）：调用方必须已在 {@link #lock} 内 */
    public void phaseEntered()
    {
        internalPhase = false;
    }

    /** 正在跑的流水线任务 id（没有则 null）；仅用于错误文案 */
    public String getPipelineJobId()
    {
        return pipelineJobId;
    }

    /** 登记流水线：调用方必须在 {@link #lock} 内、且已确认 {@link #isPipelineRunning()} 为 false */
    public void pipelineStarted(String jobId)
    {
        pipelineJobId = jobId;
    }

    /** 注销流水线：只清自己那次（重复调用幂等） */
    public void pipelineFinished(String jobId)
    {
        if (jobId != null && jobId.equals(pipelineJobId))
        {
            pipelineJobId = null;
        }
    }
}
