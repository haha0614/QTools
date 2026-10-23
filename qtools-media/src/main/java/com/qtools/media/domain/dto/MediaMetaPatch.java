package com.qtools.media.domain.dto;

import java.util.List;
import com.qtools.media.domain.MediaVideoMarker;

/**
 * 组织属性补丁（M3-A）：单条 {@code PUT /api/media/admin/video/{id}/meta} 与
 * 批量 {@code POST /api/media/admin/videos/batch-meta} 共用一个入参对象。
 *
 * <p><b>「出现 / 不出现」是语义的一部分</b>，因此每个可选字段配一个 {@code xxxPresent} 标记：
 * 字段缺失 = 不改动；字段出现（哪怕值是 null）= 覆盖，{@code rating=null} 就是清空评分。
 * 控制器负责 JSON → 本对象的类型转换（格式错误回 400），服务层负责取值范围与业务校验。</p>
 *
 * <p>合并顺序（同时出现时）：全量替换 → removeTagIds → addTagIds（同一 tagId 同时出现在
 * remove 与 add 里以 <b>add</b> 为准）。</p>
 *
 * @author qtools
 */
public class MediaMetaPatch
{
    // ------------------------------------------------------------------ 通用

    /** 批量目标视频 id（仅批量接口使用，上限 500） */
    private List<String> ids;

    /** 评分 0-10；配合 {@link #ratingPresent}，null 表示清空评分 */
    private Integer rating;

    private boolean ratingPresent;

    // ------------------------------------------------------------------ 标签

    /** 全量替换：出现即把该视频的字典标签整体换成这一组（空数组 = 清空字典标签） */
    private List<Long> tagIds;

    private boolean tagIdsPresent;

    /** 增量添加 */
    private List<Long> addTagIds;

    /** 增量摘除（不存在的关联静默忽略，保证幂等） */
    private List<Long> removeTagIds;

    // ------------------------------------------------------------------ 标记

    /** 单条接口：全量替换标记列表（出现即整表替换，空数组 = 清空） */
    private List<MediaVideoMarker> markers;

    private boolean markersPresent;

    /** 批量接口：给本批全部视频追加这些标记（每条标记广播到每个视频） */
    private List<MediaVideoMarker> markerAdd;

    /** 批量接口：只删本批视频名下的这些标记 id */
    private List<Long> markerRemoveIds;

    public List<String> getIds()
    {
        return ids;
    }

    public void setIds(List<String> ids)
    {
        this.ids = ids;
    }

    public Integer getRating()
    {
        return rating;
    }

    public void setRating(Integer rating)
    {
        this.rating = rating;
    }

    public boolean isRatingPresent()
    {
        return ratingPresent;
    }

    public void setRatingPresent(boolean ratingPresent)
    {
        this.ratingPresent = ratingPresent;
    }

    public List<Long> getTagIds()
    {
        return tagIds;
    }

    public void setTagIds(List<Long> tagIds)
    {
        this.tagIds = tagIds;
    }

    public boolean isTagIdsPresent()
    {
        return tagIdsPresent;
    }

    public void setTagIdsPresent(boolean tagIdsPresent)
    {
        this.tagIdsPresent = tagIdsPresent;
    }

    public List<Long> getAddTagIds()
    {
        return addTagIds;
    }

    public void setAddTagIds(List<Long> addTagIds)
    {
        this.addTagIds = addTagIds;
    }

    public List<Long> getRemoveTagIds()
    {
        return removeTagIds;
    }

    public void setRemoveTagIds(List<Long> removeTagIds)
    {
        this.removeTagIds = removeTagIds;
    }

    public List<MediaVideoMarker> getMarkers()
    {
        return markers;
    }

    public void setMarkers(List<MediaVideoMarker> markers)
    {
        this.markers = markers;
    }

    public boolean isMarkersPresent()
    {
        return markersPresent;
    }

    public void setMarkersPresent(boolean markersPresent)
    {
        this.markersPresent = markersPresent;
    }

    public List<MediaVideoMarker> getMarkerAdd()
    {
        return markerAdd;
    }

    public void setMarkerAdd(List<MediaVideoMarker> markerAdd)
    {
        this.markerAdd = markerAdd;
    }

    public List<Long> getMarkerRemoveIds()
    {
        return markerRemoveIds;
    }

    public void setMarkerRemoveIds(List<Long> markerRemoveIds)
    {
        this.markerRemoveIds = markerRemoveIds;
    }
}
