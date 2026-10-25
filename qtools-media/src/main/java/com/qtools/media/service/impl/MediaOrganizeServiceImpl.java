package com.qtools.media.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.media.domain.MediaTag;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.domain.MediaVideoMarker;
import com.qtools.media.domain.dto.MediaMetaPatch;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaAdminMapper;
import com.qtools.media.mapper.MediaOrganizeMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaOrganizeService;

/**
 * 媒体库组织能力 服务实现（M3-A）：标签字典 / 评分 / 时间点标记
 *
 * <p>数据落点（详见 {@code doc/media_organize.sql}）：</p>
 * <ul>
 *   <li>评分 → {@code media_video.rating}（TINYINT NULL，0-10，<b>null = 未评分</b>，0 是有效最低分）</li>
 *   <li>标签字典 → {@code media_tag}；关联写进既有的 {@code media_video_tag}
 *       （{@code facet='标签'} + {@code name} 冗余 + {@code tag_id} 指向字典），
 *       因此索引 / 详情接口的历史 facet+name 读取路径零改动</li>
 *   <li>标记 → {@code media_video_marker}</li>
 * </ul>
 *
 * <p>use_count 一律按关联行数重算而不是 +1/-1：批量增删、删字典项、重复打标签都会让增量漂移，
 * 而字典表只有几十行，重算代价可忽略。</p>
 *
 * @author qtools
 */
@Service
public class MediaOrganizeServiceImpl implements IMediaOrganizeService
{
    private static final Logger log = LoggerFactory.getLogger(MediaOrganizeServiceImpl.class);

    /** 评分刻度 0-10（10 最好） */
    private static final int RATING_MIN = 0;

    private static final int RATING_MAX = 10;

    /** 标签名去空格后长度上限（与 media_tag.name VARCHAR(32) 对齐） */
    private static final int TAG_NAME_MAX = 32;

    /** 颜色串长度上限（#RRGGBB 够用，放宽到 16 兼容 rgb() / 名称） */
    private static final int COLOR_MAX = 16;

    /** 标记名长度上限（与 media_video_marker.label VARCHAR(64) 对齐） */
    private static final int MARKER_LABEL_MAX = 64;

    /** 单条接口一次最多提交的标记数 / 单视频标记总数上限 */
    private static final int MARKERS_MAX = 100;

    /** 批量接口 id 上限 */
    private static final int BATCH_IDS_MAX = 500;

    /** 单次补丁里标签 id 上限 */
    private static final int TAG_IDS_MAX = 100;

    /** 标签层级最深 20 层（写入校验 + 路径计算的双保险上限） */
    private static final int TAG_DEPTH_MAX = 20;

    /** 批量 markerDelta.add 单次上限（每条会广播到全部 ids，避免 N×M 爆炸） */
    private static final int MARKER_ADD_MAX = 20;

    /** 批量 markerDelta.remove 单次上限 */
    private static final int MARKER_REMOVE_MAX = 500;

    /** 字典标签在 media_video_tag 里固定的 facet 值（扫描脚本从不用它） */
    private static final String FACET_TAG = "标签";

    @Autowired
    private MediaOrganizeMapper mediaOrganizeMapper;

    @Autowired
    private MediaAdminMapper mediaAdminMapper;

    /** 写入后要让索引快照失效，否则用户端最多要等 60s 缓存过期才看得到新标签 / 评分 */
    @Autowired
    private IMediaIndexService mediaIndexService;

    // ------------------------------------------------------------------ 标签字典

    @Override
    public List<Map<String, Object>> tagList()
    {
        List<MediaTag> tags = mediaOrganizeMapper.selectTagList();
        Map<Long, MediaTag> index = tagIndex(tags);
        List<Map<String, Object>> out = new ArrayList<>(tags.size());
        for (MediaTag tag : tags)
        {
            out.add(tagNode(tag, buildPath(tag, index)));
        }
        return out;
    }

    @Override
    public List<Long> descendantTagIds(Long id)
    {
        if (id == null)
        {
            throw MediaException.badRequest("missing id");
        }
        // 自顶向下 BFS：children 关系由全量字典现算，几十行量级，比递归 SQL 更容易讲清语义
        Map<Long, List<Long>> children = new LinkedHashMap<>();
        List<MediaTag> tags = mediaOrganizeMapper.selectTagList();
        boolean exists = false;
        for (MediaTag tag : tags)
        {
            if (tag.getId().equals(id))
            {
                exists = true;
            }
            if (tag.getParentId() != null)
            {
                children.computeIfAbsent(tag.getParentId(), k -> new ArrayList<>()).add(tag.getId());
            }
        }
        if (!exists)
        {
            throw MediaException.notFound("标签不存在: " + id);
        }
        List<Long> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> queue = new ArrayList<>();
        queue.add(id);
        seen.add(id);
        for (int head = 0; head < queue.size(); head++)
        {
            Long current = queue.get(head);
            out.add(current);
            for (Long child : children.getOrDefault(current, List.of()))
            {
                if (seen.add(child))
                {
                    queue.add(child);
                }
            }
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createTag(String name, String color)
    {
        String cleanName = trimToNull(name);
        if (cleanName == null)
        {
            throw MediaException.badRequest("标签名不能为空");
        }
        if (cleanName.length() > TAG_NAME_MAX)
        {
            throw MediaException.badRequest("标签名最长 " + TAG_NAME_MAX + " 个字符");
        }
        String cleanColor = trimToNull(color);
        if (cleanColor != null && cleanColor.length() > COLOR_MAX)
        {
            throw MediaException.badRequest("颜色值最长 " + COLOR_MAX + " 个字符（建议 #RRGGBB）");
        }

        MediaTag existing = mediaOrganizeMapper.selectTagByName(cleanName);
        boolean created = false;
        MediaTag tag = existing;
        if (tag == null)
        {
            MediaTag fresh = new MediaTag();
            fresh.setName(cleanName);
            fresh.setColor(cleanColor);
            mediaOrganizeMapper.insertTag(fresh);
            tag = mediaOrganizeMapper.selectTagById(fresh.getId());
            created = true;
            log.info("新建媒体标签 id={} name={}", fresh.getId(), cleanName);
        }

        Map<String, Object> out = tagNode(tag, pathOf(tag));
        out.put("created", created);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteTag(Long id)
    {
        if (id == null)
        {
            throw MediaException.badRequest("missing id");
        }
        if (mediaOrganizeMapper.selectTagById(id) == null)
        {
            throw MediaException.notFound("标签不存在: " + id);
        }
        int unlinked = mediaOrganizeMapper.deleteVideoTagByTagId(id);
        mediaOrganizeMapper.deleteTag(id);
        invalidateIndex();
        log.info("删除媒体标签 id={} 同时清理关联 {} 行", id, unlinked);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", 1);
        out.put("unlinked", unlinked);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateTag(Long id, String name, String color, boolean colorPresent, Long parentId,
            boolean parentPresent)
    {
        if (id == null)
        {
            throw MediaException.badRequest("missing id");
        }
        MediaTag current = mediaOrganizeMapper.selectTagById(id);
        if (current == null)
        {
            throw MediaException.notFound("标签不存在: " + id);
        }
        String cleanName = trimToNull(name);
        if (cleanName != null && cleanName.length() > TAG_NAME_MAX)
        {
            throw MediaException.badRequest("标签名最长 " + TAG_NAME_MAX + " 个字符");
        }
        String cleanColor = colorPresent ? trimToNull(color) : null;
        if (cleanColor != null && cleanColor.length() > COLOR_MAX)
        {
            throw MediaException.badRequest("颜色值最长 " + COLOR_MAX + " 个字符（建议 #RRGGBB）");
        }
        if (cleanName == null && !colorPresent && !parentPresent)
        {
            throw MediaException.badRequest("body 里没有任何可改字段（name / color / parentId）");
        }

        // 重名校验：改名撞上别的字典项直接 409（新建走的「重名复用」在改名场景会把两行合成一行，不能照搬）
        if (cleanName != null && !cleanName.equals(current.getName()))
        {
            MediaTag other = mediaOrganizeMapper.selectTagByName(cleanName);
            if (other != null && !other.getId().equals(id))
            {
                throw MediaException.conflict("标签名已存在: " + cleanName);
            }
            mediaOrganizeMapper.updateTagName(id, cleanName);
            // media_video_tag.name 是冗余列，不同步的话用户端索引 / 详情仍旧显示旧名字
            int synced = mediaOrganizeMapper.updateVideoTagNameByTagId(id, cleanName);
            log.info("媒体标签改名 id={} {} -> {}，同步关联行 {} 条", id, current.getName(), cleanName, synced);
        }
        if (colorPresent)
        {
            mediaOrganizeMapper.updateTagColor(id, cleanColor);
        }
        if (parentPresent)
        {
            checkParent(id, parentId);
            mediaOrganizeMapper.updateTagParent(id, parentId);
            log.info("媒体标签置父 id={} parentId={}（null = 置顶）", id, parentId);
        }
        invalidateIndex();
        MediaTag after = mediaOrganizeMapper.selectTagById(id);
        return tagNode(after, pathOf(after));
    }

    // ------------------------------------------------------------------ 单条元数据

    @Override
    public Map<String, Object> videoMeta(String videoId)
    {
        MediaVideo video = requireVideo(videoId);
        return metaNode(video);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateVideoMeta(String videoId, MediaMetaPatch patch)
    {
        MediaVideo video = requireVideo(videoId);
        if (patch == null)
        {
            throw MediaException.badRequest("missing request body");
        }
        boolean anything = patch.isRatingPresent() || patch.isTagIdsPresent() || notEmpty(patch.getAddTagIds())
                || notEmpty(patch.getRemoveTagIds()) || patch.isMarkersPresent();
        if (!anything)
        {
            throw MediaException.badRequest("body 里没有任何可写字段（rating / tagIds / addTagIds / removeTagIds / markers）");
        }

        // 1) 评分
        if (patch.isRatingPresent())
        {
            checkRating(patch.getRating());
            mediaOrganizeMapper.updateRating(videoId, patch.getRating());
            // 同步内存副本：下面 metaNode 回的是「补丁后」的元数据，不回显旧评分
            video.setRating(patch.getRating());
        }

        // 2) 标签：全量替换 → 摘除 → 追加（同一 id 同时出现在 remove 与 add 时以 add 为准）
        Set<Long> touchedTags = new LinkedHashSet<>();
        if (patch.isTagIdsPresent())
        {
            List<Long> full = distinctIds(patch.getTagIds(), "tagIds");
            mediaOrganizeMapper.deleteVideoTagsByVideoId(videoId);
            if (!full.isEmpty())
            {
                requireTagsExist(full);
                mediaOrganizeMapper.insertVideoTags(videoId, full);
            }
            log.debug("视频 {} 字典标签全量替换为 {}", videoId, full);
        }
        if (notEmpty(patch.getRemoveTagIds()))
        {
            List<Long> remove = distinctIds(patch.getRemoveTagIds(), "removeTagIds");
            mediaOrganizeMapper.deleteVideoTagsByVideoIdAndTagIds(videoId, remove);
            touchedTags.addAll(remove);
        }
        if (notEmpty(patch.getAddTagIds()))
        {
            List<Long> add = distinctIds(patch.getAddTagIds(), "addTagIds");
            requireTagsExist(add);
            mediaOrganizeMapper.insertVideoTags(videoId, add);
            touchedTags.addAll(add);
        }
        if (patch.isTagIdsPresent() || !touchedTags.isEmpty())
        {
            touchedTags.addAll(distinctIds(patch.getTagIds(), "tagIds"));
            recomputeUseCount(touchedTags);
        }

        // 3) 标记：出现即整表替换
        if (patch.isMarkersPresent())
        {
            List<MediaVideoMarker> markers = patch.getMarkers() == null ? List.of() : patch.getMarkers();
            checkMarkerList(markers);
            mediaOrganizeMapper.deleteMarkersByVideoId(videoId);
            for (MediaVideoMarker marker : markers)
            {
                checkMarkerPos(videoId, video, marker);
                mediaOrganizeMapper.insertMarker(videoId, marker.getPos(), marker.getEndPos(),
                        trimToNull(marker.getLabel()), trimToNull(marker.getColor()));
            }
        }

        if (anything)
        {
            invalidateIndex();
        }
        return metaNode(video);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> addMarker(String videoId, MediaVideoMarker marker)
    {
        MediaVideo video = requireVideo(videoId);
        if (marker == null || marker.getPos() == null)
        {
            throw MediaException.badRequest("pos 必填（秒，可为小数）");
        }
        checkMarkerFields(marker);
        checkMarkerPos(videoId, video, marker);
        int existing = mediaOrganizeMapper.selectMarkersByVideoId(videoId).size();
        if (existing >= MARKERS_MAX)
        {
            throw MediaException.badRequest("单个视频标记最多 " + MARKERS_MAX + " 条，当前已有 " + existing + " 条");
        }

        MediaVideoMarker fresh = new MediaVideoMarker();
        fresh.setVideoId(videoId);
        fresh.setPos(marker.getPos());
        fresh.setEndPos(marker.getEndPos());
        fresh.setLabel(trimToNull(marker.getLabel()));
        fresh.setColor(trimToNull(marker.getColor()));
        mediaOrganizeMapper.insertMarkerWithKey(fresh);
        invalidateIndex();
        log.debug("视频 {} 新增标记 id={} pos={}", videoId, fresh.getId(), fresh.getPos());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", fresh.getId());
        out.put("pos", fresh.getPos());
        out.put("endPos", fresh.getEndPos());
        out.put("label", fresh.getLabel());
        out.put("color", fresh.getColor());
        return out;
    }

    // ------------------------------------------------------------------ 批量

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchMeta(MediaMetaPatch patch)
    {
        if (patch == null)
        {
            throw MediaException.badRequest("missing request body");
        }
        List<String> ids = distinctVideoIds(patch.getIds());
        if (ids.isEmpty())
        {
            throw MediaException.badRequest("ids 不能为空");
        }
        if (ids.size() > BATCH_IDS_MAX)
        {
            throw MediaException.badRequest("ids 最多 " + BATCH_IDS_MAX + " 条，本次 " + ids.size() + " 条");
        }
        boolean anything = patch.isRatingPresent() || notEmpty(patch.getAddTagIds())
                || notEmpty(patch.getRemoveTagIds()) || notEmpty(patch.getMarkerAdd())
                || notEmpty(patch.getMarkerRemoveIds());
        if (!anything)
        {
            throw MediaException.badRequest("body 里没有任何可写字段（rating / addTagIds / removeTagIds / markerDelta）");
        }

        // 现存视频 + 各自时长（标记越界校验按视频自己的 dur 判断）
        Map<String, BigDecimal> durById = new LinkedHashMap<>();
        for (Map<String, Object> row : mediaOrganizeMapper.selectVideoDurList(ids))
        {
            String id = row.get("id") == null ? null : String.valueOf(row.get("id"));
            if (id == null)
            {
                continue;
            }
            Object dur = row.get("dur");
            durById.put(id, dur == null ? null : new BigDecimal(String.valueOf(dur)));
        }
        int updated = durById.size();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        if (updated == 0)
        {
            out.put("tagsAdded", 0);
            out.put("tagsRemoved", 0);
            out.put("markersAdded", 0);
            out.put("markersRemoved", 0);
            return out;
        }

        List<String> live = new ArrayList<>(durById.keySet());

        // 1) 评分（出现即批量覆盖，null = 清空）
        if (patch.isRatingPresent())
        {
            checkRating(patch.getRating());
            mediaOrganizeMapper.batchUpdateRating(live, patch.getRating());
        }

        // 2) 标签：先摘后加
        int tagsRemoved = 0;
        int tagsAdded = 0;
        Set<Long> touchedTags = new LinkedHashSet<>();
        if (notEmpty(patch.getRemoveTagIds()))
        {
            List<Long> remove = distinctIds(patch.getRemoveTagIds(), "removeTagIds");
            tagsRemoved = mediaOrganizeMapper.batchDeleteVideoTags(live, remove);
            touchedTags.addAll(remove);
        }
        if (notEmpty(patch.getAddTagIds()))
        {
            List<Long> add = distinctIds(patch.getAddTagIds(), "addTagIds");
            requireTagsExist(add);
            tagsAdded = mediaOrganizeMapper.batchInsertVideoTags(live, add);
            touchedTags.addAll(add);
        }
        if (!touchedTags.isEmpty())
        {
            // 受影响的还有「这批视频身上其它标签」，它们的使用次数同样被这次增删改动了
            touchedTags.addAll(mediaOrganizeMapper.selectTagIdsByVideoIds(live));
            recomputeUseCount(touchedTags);
        }

        // 3) markerDelta：{add:[{pos,label,color}], remove:[markerId]}
        int markersAdded = 0;
        int markersRemoved = 0;
        if (notEmpty(patch.getMarkerRemoveIds()))
        {
            List<Long> markerIds = patch.getMarkerRemoveIds();
            if (markerIds.size() > MARKER_REMOVE_MAX)
            {
                throw MediaException.badRequest("markerDelta.remove 最多 " + MARKER_REMOVE_MAX + " 条");
            }
            markersRemoved = mediaOrganizeMapper.batchDeleteMarkersByIds(live, markerIds);
        }
        if (notEmpty(patch.getMarkerAdd()))
        {
            List<MediaVideoMarker> adds = patch.getMarkerAdd();
            if (adds.size() > MARKER_ADD_MAX)
            {
                throw MediaException.badRequest("markerDelta.add 最多 " + MARKER_ADD_MAX + " 条");
            }
            for (MediaVideoMarker marker : adds)
            {
                checkMarkerPosBatch(durById, marker);
                markersAdded += mediaOrganizeMapper.batchInsertMarker(live, marker.getPos(),
                        trimToNull(marker.getLabel()), trimToNull(marker.getColor()));
            }
        }

        out.put("tagsAdded", tagsAdded);
        out.put("tagsRemoved", tagsRemoved);
        out.put("markersAdded", markersAdded);
        out.put("markersRemoved", markersRemoved);
        invalidateIndex();
        log.info("批量组织属性 ids={} updated={} tags+{} tags-{} markers+{} markers-{}", ids.size(), updated,
                tagsAdded, tagsRemoved, markersAdded, markersRemoved);
        return out;
    }

    // ------------------------------------------------------------------ 校验

    private MediaVideo requireVideo(String videoId)
    {
        String id = trimToNull(videoId);
        if (id == null)
        {
            throw MediaException.badRequest("missing video id");
        }
        MediaVideo video = mediaAdminMapper.selectVideoAnyById(id);
        if (video == null)
        {
            throw MediaException.notFound("视频不存在: " + id);
        }
        return video;
    }

    private static void checkRating(Integer rating)
    {
        if (rating == null)
        {
            // null = 清空评分，合法
            return;
        }
        if (rating < RATING_MIN || rating > RATING_MAX)
        {
            throw MediaException.badRequest("rating 必须在 " + RATING_MIN + "-" + RATING_MAX + " 之间（null 表示清空）");
        }
    }

    private void requireTagsExist(List<Long> tagIds)
    {
        int found = mediaOrganizeMapper.countTagsByIds(tagIds);
        if (found != tagIds.size())
        {
            throw MediaException.badRequest("存在未知的标签 id（提供 " + tagIds.size() + " 个，命中 " + found + " 个）");
        }
    }

    private static List<Long> distinctIds(List<Long> raw, String field)
    {
        if (raw == null)
        {
            return List.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : raw)
        {
            if (id == null || id <= 0)
            {
                throw MediaException.badRequest(field + " 里含非法 id: " + id);
            }
            set.add(id);
        }
        if (set.size() > TAG_IDS_MAX)
        {
            throw MediaException.badRequest(field + " 最多 " + TAG_IDS_MAX + " 个，本次 " + set.size() + " 个");
        }
        return new ArrayList<>(set);
    }

    private static List<String> distinctVideoIds(List<String> raw)
    {
        if (raw == null)
        {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String id : raw)
        {
            String clean = trimToNull(id);
            if (clean == null)
            {
                throw MediaException.badRequest("ids 里含空 id");
            }
            if (clean.length() > 16)
            {
                throw MediaException.badRequest("ids 里含非法 id: " + clean);
            }
            set.add(clean);
        }
        return new ArrayList<>(set);
    }

    private static void checkMarkerList(List<MediaVideoMarker> markers)
    {
        if (markers.size() > MARKERS_MAX)
        {
            throw MediaException.badRequest("单个视频标记最多 " + MARKERS_MAX + " 条，本次 " + markers.size() + " 条");
        }
        for (MediaVideoMarker marker : markers)
        {
            if (marker == null || marker.getPos() == null)
            {
                throw MediaException.badRequest("markers 每项都必须带 pos（秒）");
            }
            checkMarkerFields(marker);
        }
    }

    /** 标记的 label / color 长度校验（全量替换与新增单条标记共用） */
    private static void checkMarkerFields(MediaVideoMarker marker)
    {
        String label = trimToNull(marker.getLabel());
        if (label != null && label.length() > MARKER_LABEL_MAX)
        {
            throw MediaException.badRequest("标记名最长 " + MARKER_LABEL_MAX + " 个字符: " + label);
        }
        String color = trimToNull(marker.getColor());
        if (color != null && color.length() > COLOR_MAX)
        {
            throw MediaException.badRequest("标记颜色最长 " + COLOR_MAX + " 个字符（建议 #RRGGBB）: " + color);
        }
    }

    /**
     * 起止时间校验：pos 下限恒校验；上限只在 dur 已知时校验（dur 为 null = 扫描脚本还没拿到时长，跳过）
     *
     * <p>区间标记（{@code endPos != null}）额外要求 {@code endPos > pos}——等于 pos 是零长度区间，
     * 渲染出来是一个看不见的标记，因此按非法处理（400），而不是静默存成单点。</p>
     */
    private static void checkMarkerPos(String videoId, MediaVideo video, MediaVideoMarker marker)
    {
        BigDecimal pos = marker.getPos();
        if (pos == null || pos.signum() < 0)
        {
            throw MediaException.badRequest("标记时间点不能为负: " + pos);
        }
        BigDecimal dur = video.getDur();
        if (dur != null && pos.compareTo(dur) > 0)
        {
            throw MediaException.badRequest("标记时间点 " + pos + "s 超过视频时长 " + dur + "s（id=" + videoId + "）");
        }
        BigDecimal endPos = marker.getEndPos();
        if (endPos == null)
        {
            return;
        }
        if (endPos.signum() < 0)
        {
            throw MediaException.badRequest("标记结束时间不能为负: " + endPos);
        }
        if (endPos.compareTo(pos) <= 0)
        {
            throw MediaException.badRequest("标记结束时间 " + endPos + "s 必须大于起始时间 " + pos + "s");
        }
        if (dur != null && endPos.compareTo(dur) > 0)
        {
            throw MediaException.badRequest("标记结束时间 " + endPos + "s 超过视频时长 " + dur + "s（id=" + videoId + "）");
        }
    }

    private static void checkMarkerPosBatch(Map<String, BigDecimal> durById, MediaVideoMarker marker)
    {
        BigDecimal pos = marker.getPos();
        if (pos == null || pos.signum() < 0)
        {
            throw MediaException.badRequest("markerDelta.add 的时间点不能为负: " + pos);
        }
        String label = trimToNull(marker.getLabel());
        if (label != null && label.length() > MARKER_LABEL_MAX)
        {
            throw MediaException.badRequest("标记名最长 " + MARKER_LABEL_MAX + " 个字符: " + label);
        }
        String color = trimToNull(marker.getColor());
        if (color != null && color.length() > COLOR_MAX)
        {
            throw MediaException.badRequest("标记颜色最长 " + COLOR_MAX + " 个字符（建议 #RRGGBB）: " + color);
        }
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : durById.entrySet())
        {
            BigDecimal dur = entry.getValue();
            if (dur != null && pos.compareTo(dur) > 0)
            {
                bad.add(entry.getKey() + "(" + dur + "s)");
            }
        }
        if (!bad.isEmpty())
        {
            throw MediaException.badRequest("标记时间点 " + pos + "s 超过部分视频时长，批量为全成功/全失败，"
                    + "冲突视频（最多列 3 个）: " + String.join(", ", bad.subList(0, Math.min(3, bad.size()))));
        }
    }

    // ------------------------------------------------------------------ 输出 / 工具

    private Map<String, Object> metaNode(MediaVideo video)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rating", video.getRating());

        List<Map<String, Object>> tags = new ArrayList<>();
        for (MediaTag tag : mediaOrganizeMapper.selectTagsByVideoId(video.getId()))
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", tag.getId());
            node.put("name", tag.getName());
            node.put("color", tag.getColor());
            tags.add(node);
        }
        out.put("tags", tags);

        List<Map<String, Object>> markers = new ArrayList<>();
        for (MediaVideoMarker marker : mediaOrganizeMapper.selectMarkersByVideoId(video.getId()))
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", marker.getId());
            node.put("pos", marker.getPos());
            // 单点标记回 null（不是省略字段）：前端据此区分「点」与「区间」
            node.put("endPos", marker.getEndPos());
            node.put("label", marker.getLabel());
            node.put("color", marker.getColor());
            markers.add(node);
        }
        out.put("markers", markers);
        return out;
    }

    private static Map<String, Object> tagNode(MediaTag tag, String path)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", tag.getId());
        node.put("name", tag.getName());
        node.put("parentId", tag.getParentId());
        node.put("color", tag.getColor());
        node.put("useCount", tag.getUseCount() == null ? 0 : tag.getUseCount());
        node.put("path", path);
        return node;
    }

    /** id → 标签，供路径计算与父链校验用（字典只有几十行，一次全量取回即可） */
    private static Map<Long, MediaTag> tagIndex(List<MediaTag> tags)
    {
        Map<Long, MediaTag> index = new LinkedHashMap<>();
        for (MediaTag tag : tags)
        {
            index.put(tag.getId(), tag);
        }
        return index;
    }

    /**
     * 从顶层到自身的名字链（{@code 演员/日本/XX}）
     *
     * <p>最多上溯 {@link #TAG_DEPTH_MAX} 层，遇到环立刻截断成 {@code ...} 前缀——
     * 库里理论上不会出现环（写入时校验），但读路径不能因为一行脏数据就死循环。</p>
     */
    private static String buildPath(MediaTag tag, Map<Long, MediaTag> index)
    {
        List<String> names = new ArrayList<>();
        MediaTag current = tag;
        boolean truncated = false;
        for (int depth = 0; current != null; depth++)
        {
            if (depth >= TAG_DEPTH_MAX)
            {
                truncated = true;
                break;
            }
            names.add(current.getName());
            Long parentId = current.getParentId();
            if (parentId == null)
            {
                break;
            }
            MediaTag parent = index.get(parentId);
            if (parent == null)
            {
                break;
            }
            if (parent.getId().equals(tag.getId()))
            {
                truncated = true;
                break;
            }
            current = parent;
        }
        java.util.Collections.reverse(names);
        String path = String.join("/", names);
        return truncated ? "..." + path : path;
    }

    /** 单条返回时现查父链（新建 / 改完之后回显用；避开一次全量字典） */
    private String pathOf(MediaTag tag)
    {
        if (tag == null)
        {
            return null;
        }
        Map<Long, MediaTag> index = new LinkedHashMap<>();
        index.put(tag.getId(), tag);
        Long parentId = tag.getParentId();
        int depth = 0;
        while (parentId != null && depth < TAG_DEPTH_MAX)
        {
            MediaTag parent = index.get(parentId);
            if (parent == null)
            {
                parent = mediaOrganizeMapper.selectTagById(parentId);
                if (parent == null)
                {
                    break;
                }
                index.put(parent.getId(), parent);
            }
            parentId = parent.getParentId();
            depth++;
        }
        return buildPath(tag, index);
    }

    /**
     * 置父校验：不许自引用、不许成环、深度不超过 {@link #TAG_DEPTH_MAX} 层
     *
     * <p>MySQL 8 的 CHECK 不能跨行查表，所以这条约束只能落在服务层；
     * 环检测沿 parent 链上溯，最多走 20 层就够（同一层上限）。
     */
    private void checkParent(Long id, Long parentId)
    {
        if (parentId == null)
        {
            return;
        }
        if (parentId.equals(id))
        {
            throw MediaException.badRequest("不能把标签挂到自己下面: " + id);
        }
        Map<Long, MediaTag> index = tagIndex(mediaOrganizeMapper.selectTagList());
        MediaTag parent = index.get(parentId);
        if (parent == null)
        {
            throw MediaException.badRequest("父标签不存在: " + parentId);
        }
        int depth = 1;
        MediaTag cursor = parent;
        Set<Long> visited = new LinkedHashSet<>();
        while (cursor != null)
        {
            if (!visited.add(cursor.getId()))
            {
                throw MediaException.badRequest("标签层级已成环，不能再挂: " + parentId);
            }
            if (cursor.getId().equals(id))
            {
                throw MediaException.badRequest("不能把标签挂到自己的后代下面（会成环）: " + id + " -> " + parentId);
            }
            if (cursor.getParentId() == null)
            {
                break;
            }
            cursor = index.get(cursor.getParentId());
            depth++;
            if (depth > TAG_DEPTH_MAX)
            {
                throw MediaException.badRequest("标签层级最深 " + TAG_DEPTH_MAX + " 层");
            }
        }
    }

    private void recomputeUseCount(Set<Long> tagIds)
    {
        if (tagIds == null || tagIds.isEmpty())
        {
            return;
        }
        mediaOrganizeMapper.recomputeUseCount(new ArrayList<>(tagIds));
    }

    private void invalidateIndex()
    {
        try
        {
            mediaIndexService.invalidateSnapshot();
        }
        catch (RuntimeException e)
        {
            // 索引失效失败不能回滚已经写好的组织属性：最多让用户端晚 60s 看到
            log.warn("索引快照失效失败: {}", e.getMessage());
        }
    }

    private static boolean notEmpty(List<?> list)
    {
        return list != null && !list.isEmpty();
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
