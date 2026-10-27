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
}
