package com.qtools.media.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.enums.BusinessType;
import com.qtools.media.service.IMediaScanIngestService;

/**
 * 扫描入库 管理端接口（前缀 /api/media/admin/scan/jobs）
 *
 * <p>为什么单独一个控制器：{@code POST /scan} 已经在 {@code MediaAdminController} 里（按 mode 的维护任务），
 * 这里只挂<b>新的一段路径</b> {@code /scan/jobs/**}，两段路径不重叠
 * （{@code GET /scan/{jobId}} 是「按 id 查维护任务」，{@code GET /scan/jobs/{jobId}} 是「查扫描入库任务」）。</p>
 *
 * <p>权限：启动 / 取消是<b>写操作</b>（{@code media:admin:edit}）；查状态与日志是<b>只读</b>，
 * 用页面菜单本身的 {@code media:admin:list}——只读进度不该比打开页面要求更高。
 * 启动入口 {@code POST /scan} 同时接受 {@code media:admin:scan}（旧的「重建索引」权限）。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/media/admin")
public class MediaScanIngestController
{
    @Autowired
    private IMediaScanIngestService mediaScanIngestService;

    /** 当前正在跑的扫描入库任务；没有任务时回 null（前端拿它做「进页面自动接管」） */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/scan/jobs/active")
    public Map<String, Object> active()
    {
        return mediaScanIngestService.active();
    }

    /**
     * 指定任务的状态与日志尾部
     *
     * <p>{@code tail} 限制回多少行（默认 2000 = 环形缓冲全量；前端轮询时只要最后 500 行）。
     * 任务只存在内存：进程重启后查旧 id 一律 404。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/scan/jobs/{jobId}")
    public Map<String, Object> job(@PathVariable("jobId") String jobId,
            @RequestParam(value = "tail", required = false) Integer tail)
    {
        return mediaScanIngestService.getJob(jobId, tail);
    }

    /** 协作式取消：destroy 子进程，等任务线程把 status 落成 cancelled 再回 */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-扫描入库取消", businessType = BusinessType.OTHER)
    @PostMapping("/scan/jobs/{jobId}/cancel")
    public Map<String, Object> cancel(@PathVariable("jobId") String jobId)
    {
        return mediaScanIngestService.cancel(jobId);
    }
}
