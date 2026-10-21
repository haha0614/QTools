package com.qtools.media.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaScanJob;

/**
 * 媒体扫描任务 数据层
 *
 * @author qtools
 */
public interface MediaScanJobMapper
{
    /** 插入任务（running），回填自增 id */
    int insertJob(MediaScanJob job);

    /** 当前仍在 running 的任务条数，用于「同一时刻只允许 1 个」的并发保护 */
    int countRunning();

    /** 最近一次任务（任意状态） */
    MediaScanJob selectLatest();

    /** 按 id 查询任务 */
    MediaScanJob selectJobById(@Param("id") Long id);

    /** 最近 N 个任务（newest first），不含 log 体 */
    List<MediaScanJob> selectRecentJobs(@Param("limit") int limit);

    /** 运行中刷新进度 */
    int updateProgress(@Param("id") Long id, @Param("total") Integer total, @Param("done") Integer done);

    /** 结束任务：置 done/failed + finished_at + log 尾部 */
    int finishJob(@Param("id") Long id, @Param("status") String status, @Param("total") Integer total,
            @Param("done") Integer done, @Param("log") String log, @Param("finishedAt") Date finishedAt);
}
