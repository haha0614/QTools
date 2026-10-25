package com.qtools.media.domain.dto;

import java.util.List;

/**
 * 一次扫描 / 维护任务的请求参数（{@code POST /api/media/admin/scan} 的 body）
 *
 * <p>字段只描述「要做什么」，<b>不携带任何子进程参数</b>：真正拼进命令行的开关一律由
 * {@code MediaScanServiceImpl} 按 mode 从白名单追加，前端无法把任意参数塞进子进程。</p>
 *
 * @author qtools
 */
public class MediaScanRequest
{
    /** 旧参数：mode 为空时兜底（full → scan，thumb → thumb） */
    private String type;

    /** {@code scan | thumb | sprite | preview | sprite,preview | gc | enrich | backfill-hash | fix-poster | export} */
    private String mode;

    /** mode=gc 必须显式 true（会真删 v/ s/ pv2/ 下的孤儿派生文件） */
    private boolean confirm;

    /** 只处理前 n 条（0 或 null = 不限）；仅 thumb / sprite / preview / enrich 生效 */
    private Integer limit;

    /** mode=scan 专用：dir | prefix（覆盖 scanArgs 里已有的 --album-mode=） */
    private String albumMode;

    /** mode=scan 专用：按目录名排除，逗号分隔（追加 --exclude=） */
    private String exclude;

    /** mode=thumb / sprite / preview 专用：关闭 mtime 新鲜度判断（追加 --no-fresh） */
    private boolean noFresh;

    /** mode=thumb / sprite / preview 专用：忽略增量、全部重建（追加 --force） */
    private boolean force;

    /**
     * mode=thumb / sprite / preview 专用：只处理这些 videoId（≤200，追加 {@code --video=a,b}）
     *
     * <p>「勾选想生成的视频」的入口：脚本对库里查不到的 id 只 WARN 并跳过，不报错——
     * 前端拿到的是管理端列表里的 id，列表本身就可能滞后于一次删除 / 改名。</p>
     */
    private List<String> videoIds;

    /** mode=export 专用：后端生成的产物目录（{@code <exportPath>/<yyyyMMdd-HHmmss>}），不接受前端传值 */
    private String exportDir;

    /**
     * 触发来源，只由服务端内部写入（文件监听写 {@code AUTO}，管理端手工触发留 null）
     *
     * <p>不来自任何前端 body：控制器从不解析它，否则「自动 / 手动」的来源标识就成了客户端可伪造的字段。
     * 非空时 {@code MediaScanServiceImpl.runJob} 在 job 日志首行写 {@code <origin> 触发：<triggerNote>}。</p>
     */
    private String origin;

    /** 来源说明（写进日志首行），如 {@code E:/111/视频 变化 3 个事件} */
    private String triggerNote;

    public MediaScanRequest()
    {
    }

    public MediaScanRequest(String type, String mode, boolean confirm, Integer limit)
    {
        this.type = type;
        this.mode = mode;
        this.confirm = confirm;
        this.limit = limit;
    }

    /** 提交给执行线程前复制一份：执行期间控制器不再持有同一个可变对象 */
    public MediaScanRequest copy()
    {
        MediaScanRequest copy = new MediaScanRequest(type, mode, confirm, limit);
        copy.albumMode = albumMode;
        copy.exclude = exclude;
        copy.noFresh = noFresh;
        copy.force = force;
        copy.videoIds = videoIds == null ? null : List.copyOf(videoIds);
        copy.exportDir = exportDir;
        copy.origin = origin;
        copy.triggerNote = triggerNote;
        return copy;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getMode()
    {
        return mode;
    }

    public void setMode(String mode)
    {
        this.mode = mode;
    }

    public boolean isConfirm()
    {
        return confirm;
    }

    public void setConfirm(boolean confirm)
    {
        this.confirm = confirm;
    }

    public Integer getLimit()
    {
        return limit;
    }

    public void setLimit(Integer limit)
    {
        this.limit = limit;
    }

    public String getAlbumMode()
    {
        return albumMode;
    }

    public void setAlbumMode(String albumMode)
    {
        this.albumMode = albumMode;
    }

    public String getExclude()
    {
        return exclude;
    }

    public void setExclude(String exclude)
    {
        this.exclude = exclude;
    }

    public boolean isNoFresh()
    {
        return noFresh;
    }

    public void setNoFresh(boolean noFresh)
    {
        this.noFresh = noFresh;
    }

    public boolean isForce()
    {
        return force;
    }

    public void setForce(boolean force)
    {
        this.force = force;
    }

    public List<String> getVideoIds()
    {
        return videoIds;
    }

    public void setVideoIds(List<String> videoIds)
    {
        this.videoIds = videoIds;
    }

    public String getExportDir()
    {
        return exportDir;
    }

    public void setExportDir(String exportDir)
    {
        this.exportDir = exportDir;
    }

    public String getOrigin()
    {
        return origin;
    }

    public void setOrigin(String origin)
    {
        this.origin = origin;
    }

    public String getTriggerNote()
    {
        return triggerNote;
    }

    public void setTriggerNote(String triggerNote)
    {
        this.triggerNote = triggerNote;
    }
}
