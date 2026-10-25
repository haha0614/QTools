package com.qtools.media.service.impl;

import java.text.SimpleDateFormat;
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
import com.qtools.media.domain.MediaStudio;
import com.qtools.media.domain.dto.MediaStudioPatch;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaStudioMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaStudioService;

/**
 * 媒体工作室 服务实现（P5-A）
 *
 * <p>数据落点（详见 {@code doc/media_studio.sql}）：</p>
 * <ul>
 *   <li>工作室字典 → {@code media_studio}（name 唯一）</li>
 *   <li>视频 ↔ 工作室 → {@code media_video_studio}，PK(video_id, studio_id) 天然去重</li>
 * </ul>
 *
 * <p>use_count 一律按关联行数重算而不是 +1/-1：批量替换、删工作室、重复绑定都会让增量漂移，
 * 而字典表只有几十行，重算代价可忽略（与标签 / 人物同一套理由）。</p>
 *
 * @author qtools
 */
@Service
public class MediaStudioServiceImpl implements IMediaStudioService
{
    private static final Logger log = LoggerFactory.getLogger(MediaStudioServiceImpl.class);

    /** 工作室名去空格后长度上限（API 契约；DB 列宽 120 是余量） */
    private static final int NAME_MAX = 64;

    private static final int ALIAS_MAX = 128;

    private static final int NOTE_MAX = 512;

    /** 与 media_studio.avatar_path VARCHAR(400) 对齐 */
    private static final int AVATAR_MAX = 400;

    /** 单个视频一次最多绑定的工作室数 */
    private static final int STUDIO_IDS_MAX = 50;

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 200;

    private static final String DATE_PATTERN = "yyyy-MM-dd";

    @Autowired
    private MediaStudioMapper mediaStudioMapper;

    /** 工作室写操作会改索引（videos[].studios），写完必须失效快照 */
    @Autowired
    private IMediaIndexService mediaIndexService;

    // ------------------------------------------------------------------ 管理端：字典

    @Override
    public List<Map<String, Object>> studioList()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaStudio studio : mediaStudioMapper.selectStudioList())
        {
            out.add(studioNode(studio, true));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createStudio(MediaStudioPatch patch)
    {
        if (patch == null || !patch.isNamePresent())
        {
            throw MediaException.badRequest("name 必填（去首尾空格后 1-" + NAME_MAX + " 字符）");
        }
        String name = requireName(patch.getName());
        if (mediaStudioMapper.selectStudioByName(name) != null)
        {
            throw MediaException.conflict("工作室名已存在: " + name);
        }
        MediaStudio fresh = new MediaStudio();
        fresh.setName(name);
        fresh.setAlias(limit(patch.getAlias(), ALIAS_MAX, "alias"));
        fresh.setNote(limit(patch.getNote(), NOTE_MAX, "note"));
        fresh.setAvatarPath(checkAvatar(patch.getAvatarPath()));
        fresh.setSort(patch.getSort());
        if (patch.isHiddenPresent())
        {
            fresh.setHidden(hiddenFlag(patch.getHidden()));
        }
        mediaStudioMapper.insertStudio(fresh);
        invalidateIndex();
        log.info("新建媒体工作室 id={} name={}", fresh.getId(), name);
        return studioNode(mediaStudioMapper.selectStudioDetail(fresh.getId()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateStudio(Long id, MediaStudioPatch patch)
    {
        MediaStudio current = requireStudio(id);
        if (patch == null || !(patch.isNamePresent() || patch.isAliasPresent() || patch.isNotePresent()
                || patch.isAvatarPresent() || patch.isSortPresent() || patch.isHiddenPresent()))
        {
            throw MediaException.badRequest(
                    "body 里没有任何可改字段（name / alias / note / avatarPath / sort / hidden）");
        }

        // 覆盖式 UPDATE：未出现的字段沿用库里的现值，出现的字段（含 null）直接覆盖
        MediaStudio target = new MediaStudio();
        target.setId(id);
        target.setName(current.getName());
        target.setAlias(current.getAlias());
        target.setNote(current.getNote());
        target.setAvatarPath(current.getAvatarPath());
        target.setSort(current.getSort());
        target.setHidden(current.getHidden());

        if (patch.isNamePresent())
        {
            String name = requireName(patch.getName());
            if (!name.equals(current.getName()))
            {
                MediaStudio other = mediaStudioMapper.selectStudioByName(name);
                if (other != null && !other.getId().equals(id))
                {
                    throw MediaException.conflict("工作室名已存在: " + name);
                }
                log.info("媒体工作室改名 id={} {} -> {}", id, current.getName(), name);
            }
            target.setName(name);
        }
        if (patch.isAliasPresent())
        {
            target.setAlias(limit(patch.getAlias(), ALIAS_MAX, "alias"));
        }
        if (patch.isNotePresent())
        {
            target.setNote(limit(patch.getNote(), NOTE_MAX, "note"));
        }
        if (patch.isAvatarPresent())
        {
            target.setAvatarPath(checkAvatar(patch.getAvatarPath()));
        }
        if (patch.isSortPresent())
        {
            target.setSort(patch.getSort());
        }
        if (patch.isHiddenPresent())
        {
            target.setHidden(hiddenFlag(patch.getHidden()));
        }
        mediaStudioMapper.updateStudio(target);
        invalidateIndex();
        return studioNode(mediaStudioMapper.selectStudioDetail(id), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteStudio(Long id)
    {
        requireStudio(id);
        long videoRefs = mediaStudioMapper.countVideoRefsByStudio(id);
        if (videoRefs > 0)
        {
            throw MediaException.badRequest("工作室仍被 " + videoRefs + " 个视频引用，不能删除（先解绑再删）");
        }
        mediaStudioMapper.deleteStudio(id);
        invalidateIndex();
        log.info("删除媒体工作室 id={}", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", 1);
        out.put("id", id);
        return out;
    }

    // ------------------------------------------------------------------ 管理端：绑定

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> replaceVideoStudios(String videoId, List<Long> studioIds)
    {
        if (videoId == null || videoId.trim().isEmpty())
        {
            throw MediaException.badRequest("missing video id");
        }
        if (mediaStudioMapper.countVideoById(videoId) == 0)
        {
            throw MediaException.notFound("视频不存在: " + videoId);
        }
        List<Long> target = distinctIds(studioIds);
        requireStudiosExist(target);

        // 旧绑定要在删除前记下来，否则 use_count 漏算「刚被换成别的」的那些工作室
        Set<Long> touched = new LinkedHashSet<>(mediaStudioMapper.selectStudioIdsByVideoId(videoId));
        mediaStudioMapper.deleteVideoStudios(videoId);
        for (Long studioId : target)
        {
            mediaStudioMapper.insertVideoStudio(videoId, studioId);
            touched.add(studioId);
        }
        if (!touched.isEmpty())
        {
            mediaStudioMapper.recomputeStudioUseCount(new ArrayList<>(touched));
        }
        invalidateIndex();
        log.info("视频 {} 工作室全量替换为 {}", videoId, target);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", target.size());
        out.put("id", videoId);
        out.put("studios", mediaStudioMapper.selectVideoStudios(videoId));
        return out;
    }

    // ------------------------------------------------------------------ 站端（只读）

    @Override
    public List<Map<String, Object>> studioBriefList()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaStudio studio : mediaStudioMapper.selectStudioVisibleList())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", studio.getId());
            node.put("name", studio.getName());
            node.put("alias", studio.getAlias());
            node.put("useCount", studio.getUseCount() == null ? 0 : studio.getUseCount());
            node.put("videoCount", studio.getVideoCount() == null ? 0L : studio.getVideoCount());
            out.add(node);
        }
        return out;
    }

    @Override
    public Map<String, Object> studioDetail(Long id)
    {
        MediaStudio studio = requireStudio(id);
        if (studio.getHidden() != null && studio.getHidden() == 1)
        {
            // 隐藏的工作室对站端视同不存在：不泄露「有这个名字但不能看」
            throw MediaException.notFound("工作室不存在: " + id);
        }
        return studioNode(studio, false);
    }

    @Override
    public Map<String, Object> studioVideos(Long id, int page, int size)
    {
        studioDetail(id);
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", mediaStudioMapper.selectStudioVideoPage(id, (safePage - 1) * safeSize, safeSize));
        out.put("total", mediaStudioMapper.countStudioVideos(id));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    // ------------------------------------------------------------------ 输出 / 校验 / 工具

    /**
     * 工作室节点
     *
     * @param admin true = 管理端（多出 sort / hidden / createdAt）；false = 站端只读
     */
    private Map<String, Object> studioNode(MediaStudio studio, boolean admin)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", studio.getId());
        node.put("name", studio.getName());
        node.put("alias", studio.getAlias());
        node.put("note", studio.getNote());
        node.put("avatarPath", studio.getAvatarPath());
        node.put("useCount", studio.getUseCount() == null ? 0 : studio.getUseCount());
        node.put("videoCount", studio.getVideoCount() == null ? 0L : studio.getVideoCount());
        if (admin)
        {
            node.put("sort", studio.getSort() == null ? 0 : studio.getSort());
            node.put("hidden", studio.getHidden() != null && studio.getHidden() == 1);
            node.put("createdAt", formatDate(studio.getCreatedAt()));
        }
        return node;
    }

    private MediaStudio requireStudio(Long id)
    {
        if (id == null)
        {
            throw MediaException.badRequest("missing id");
        }
        MediaStudio studio = mediaStudioMapper.selectStudioDetail(id);
        if (studio == null)
        {
            throw MediaException.notFound("工作室不存在: " + id);
        }
        return studio;
    }

    private void requireStudiosExist(List<Long> studioIds)
    {
        if (studioIds.isEmpty())
        {
            return;
        }
        long found = mediaStudioMapper.countStudiosByIds(studioIds);
        if (found != studioIds.size())
        {
            throw MediaException.badRequest("studioIds 里有不存在的工作室 id（提交 " + studioIds.size()
                    + " 个，实际命中 " + found + " 个）");
        }
    }

    private static String requireName(String raw)
    {
        String name = trimToNull(raw);
        if (name == null)
        {
            throw MediaException.badRequest("name 必填（去首尾空格后 1-" + NAME_MAX + " 字符）");
        }
        if (name.length() > NAME_MAX)
        {
            throw MediaException.badRequest("name 最长 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    /** 头像只存路径：允许站内相对路径（/profile/upload/...）与 http(s) URL，其余一律 400 */
    private static String checkAvatar(String raw)
    {
        String path = trimToNull(raw);
        if (path == null)
        {
            return null;
        }
        if (path.length() > AVATAR_MAX)
        {
            throw MediaException.badRequest("avatarPath 最长 " + AVATAR_MAX + " 个字符");
        }
        String lower = path.toLowerCase();
        boolean ok = lower.startsWith("http://") || lower.startsWith("https://")
                || (path.startsWith("/") && !lower.startsWith("//"));
        if (!ok)
        {
            throw MediaException.badRequest("avatarPath 必须是 http(s) URL 或站内绝对路径（以 / 开头）: " + path);
        }
        return path;
    }

    private static Integer hiddenFlag(Boolean hidden)
    {
        return Boolean.TRUE.equals(hidden) ? Integer.valueOf(1) : Integer.valueOf(0);
    }

    private static String limit(String value, int max, String field)
    {
        String clean = trimToNull(value);
        if (clean != null && clean.length() > max)
        {
            throw MediaException.badRequest(field + " 最长 " + max + " 个字符");
        }
        return clean;
    }

    private static List<Long> distinctIds(List<Long> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return List.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : ids)
        {
            if (id == null)
            {
                throw MediaException.badRequest("studioIds 里含空 id");
            }
            set.add(id);
        }
        if (set.size() > STUDIO_IDS_MAX)
        {
            throw MediaException.badRequest("studioIds 一次最多 " + STUDIO_IDS_MAX + " 个");
        }
        return new ArrayList<>(set);
    }

    private static String formatDate(java.util.Date date)
    {
        return date == null ? null : new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    private void invalidateIndex()
    {
        try
        {
            mediaIndexService.invalidateSnapshot();
        }
        catch (RuntimeException e)
        {
            // 索引失效失败不能回滚已经写好的绑定：最多让用户端晚 60s 看到
            log.warn("索引快照失效失败: {}", e.getMessage());
        }
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
