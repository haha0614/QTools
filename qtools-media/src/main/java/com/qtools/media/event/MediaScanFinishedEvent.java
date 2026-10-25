package com.qtools.media.event;

/**
 * 扫描任务结束事件（{@code media_scan_job} 刚被置 done / failed）
 *
 * <p>发布点只有一个：{@code MediaScanServiceImpl.runJob} 在 {@code finishJob} +
 * running 标记归零之后发出。存在的理由是<b>解除「自动扫描」与扫描实现之间的直接依赖</b>：
 * 文件监听（{@code MediaWatchService}）需要知道「刚才那次跑完了，可以补跑我攒下的 pending」，
 * 但它默认不注册（{@code qtools.media.watch.enabled=false}），扫描服务不能反过来硬依赖它。
 * 监听未开启时没有任何监听者，{@code publishEvent} 是一次空操作。</p>
 *
 * @author qtools
 */
public class MediaScanFinishedEvent
{
    /** 结束的任务 id（{@code media_scan_job.id}） */
    private final long jobId;

    /** 落库的 type（scan → full，其余与 mode 同名） */
    private final String type;

    /** done / failed */
    private final String status;

    public MediaScanFinishedEvent(long jobId, String type, String status)
    {
        this.jobId = jobId;
        this.type = type;
        this.status = status;
    }

    public long getJobId()
    {
        return jobId;
    }

    public String getType()
    {
        return type;
    }

    public String getStatus()
    {
        return status;
    }
}
