package com.qtools.media.domain;

import java.util.Date;

/**
 * 媒体扫描任务 media_scan_job
 *
 * @author qtools
 */
public class MediaScanJob
{
    private Long id;

    /** full | thumb */
    private String type;

    /** running | done | failed */
    private String status;

    private Integer total;

    private Integer done;

    private Date startedAt;

    private Date finishedAt;

    /** 子进程输出尾部（最多 {@code MediaScanServiceImpl#LOG_MAX_LINES} 行） */
    private String log;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public Integer getTotal()
    {
        return total;
    }

    public void setTotal(Integer total)
    {
        this.total = total;
    }

    public Integer getDone()
    {
        return done;
    }

    public void setDone(Integer done)
    {
        this.done = done;
    }

    public Date getStartedAt()
    {
        return startedAt;
    }

    public void setStartedAt(Date startedAt)
    {
        this.startedAt = startedAt;
    }

    public Date getFinishedAt()
    {
        return finishedAt;
    }

    public void setFinishedAt(Date finishedAt)
    {
        this.finishedAt = finishedAt;
    }

    public String getLog()
    {
        return log;
    }

    public void setLog(String log)
    {
        this.log = log;
    }
}
