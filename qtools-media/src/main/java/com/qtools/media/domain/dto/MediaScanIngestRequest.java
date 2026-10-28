package com.qtools.media.domain.dto;

/**
 * 扫描入库请求（{@code POST /api/media/admin/scan} body 的「扫描入库」形状）
 *
 * <p>与 {@link MediaScanRequest} 的区别：那个是「按 mode 跑维护任务」（参数由后端按 mode 从白名单追加），
 * 这个只管一件事——<b>把某个库根目录里的文件遍历一遍写进库</b>。因此这里只出现「扫哪个根」
 * 与几个开关，<b>没有 path 字段</b>：媒体根只能来自 {@code media_root.path}，
 * 客户端传路径一律不认（否则等于给了一个任意目录的读取入口）。</p>
 *
 * @author qtools
 */
public class MediaScanIngestRequest
{
    /** 库根 id（必填）：{@code media_root.id}，且 {@code kind} 必须是 root（thumbs → 400） */
    private Long rootId;

    /** 只统计不写库（脚本 --dry-run：不连库、不读库内状态，所见即全部新建） */
    private Boolean dryRun;

    /** 扫视频（缺省 true） */
    private Boolean withVideo;

    /** 扫图片 / 相册（缺省 true） */
    private Boolean withPhoto;

    /** 最多处理多少个视频 / 多少张照片（0 或 null = 不限；正数上限 10000） */
    private Integer limit;

    /**
     * 强制：把「同 dir_key 的 0 图旧相册」在同一事务里清掉（脚本 --purge-unclaimed）
     *
     * <p>scan-media.js 遇到这种旧行默认<b>报错停下</b>（同一目录会并存两本册，旧版本就是这么留下
     * 永远打不开的空相册），只有显式给这个开关才继续。前端「强制」勾上就是把这条路放开。</p>
     */
    private Boolean force;

    /**
     * 是否跑完整条流水线（P5.97，缺省 true）
     *
     * <p>true = 扫描成功后自动接上「补哈希 → 补元数据 → 封面缩略图 → 重建索引快照」；
     * false = 只扫盘写库（老行为，给命令行 / 排障用）。dry-run 恒为 false：预演什么都不写。</p>
     */
    private Boolean pipeline;

    /** 是否补 content_hash（缺省 true）：脚本只补空值，扫描阶段已写入时这一阶段自动跳过 */
    private Boolean hash;

    /** 是否在最后重建索引快照（缺省 true）：失效 + 立刻重算一次，站端下一次请求就是新内容 */
    private Boolean rebuildIndex;

    /**
     * 派生资源档位（缺省 {@code thumb}）
     *
     * <ul>
     *   <li>{@code none}：不生成任何派生资源（只入库）；</li>
     *   <li>{@code thumb}：封面 + 图片缩略图（默认，最省时间）；</li>
     *   <li>{@code full}：再加精灵图 + 预览片段（全片解码，慢两个数量级）。</li>
     * </ul>
     */
    private String derive;

    public Long getRootId()
    {
        return rootId;
    }

    public void setRootId(Long rootId)
    {
        this.rootId = rootId;
    }

    public Boolean getDryRun()
    {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun)
    {
        this.dryRun = dryRun;
    }

    public Boolean getWithVideo()
    {
        return withVideo;
    }

    public void setWithVideo(Boolean withVideo)
    {
        this.withVideo = withVideo;
    }

    public Boolean getWithPhoto()
    {
        return withPhoto;
    }

    public void setWithPhoto(Boolean withPhoto)
    {
        this.withPhoto = withPhoto;
    }

    public Integer getLimit()
    {
        return limit;
    }

    public void setLimit(Integer limit)
    {
        this.limit = limit;
    }

    public Boolean getForce()
    {
        return force;
    }

    public void setForce(Boolean force)
    {
        this.force = force;
    }

    public Boolean getPipeline()
    {
        return pipeline;
    }

    public void setPipeline(Boolean pipeline)
    {
        this.pipeline = pipeline;
    }

    public Boolean getHash()
    {
        return hash;
    }

    public void setHash(Boolean hash)
    {
        this.hash = hash;
    }

    public Boolean getRebuildIndex()
    {
        return rebuildIndex;
    }

    public void setRebuildIndex(Boolean rebuildIndex)
    {
        this.rebuildIndex = rebuildIndex;
    }

    public String getDerive()
    {
        return derive;
    }

    public void setDerive(String derive)
    {
        this.derive = derive;
    }
}
