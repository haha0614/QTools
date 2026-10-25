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
import com.qtools.media.domain.MediaPerson;
import com.qtools.media.domain.dto.MediaPersonPatch;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaPersonService;

/**
 * 媒体人物 服务实现（P3-A）
 *
 * <p>数据落点（详见 {@code doc/media_person_tags.sql}）：</p>
 * <ul>
 *   <li>人物字典 → {@code media_person}（P3-A 补了 alias / note / avatar_path / gender / birthday / use_count）</li>
 *   <li>视频 ↔ 人物 → {@code media_video_actor}：{@code person_id} 指向字典、{@code name} 冗余人名、
 *       {@code role} 存角色；{@code person_id IS NULL} 的扫描历史行永远不动</li>
 *   <li>相册 ↔ 人物 → {@code media_album_person}（多对多）+ {@code media_album.person_id}
 *       （单选镜像列 = 多人物里的第一个）</li>
 * </ul>
 *
 * <p>use_count 一律按关联行数重算而不是 +1/-1：批量替换、删人物、重复绑定都会让增量漂移，
 * 而人物表只有几十行，重算代价可忽略（与标签 use_count 同一套理由）。</p>
 *
 * @author qtools
 */
@Service
public class MediaPersonServiceImpl implements IMediaPersonService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonServiceImpl.class);

    /** 人物名去空格后长度上限（API 契约；DB 列宽 120 是余量） */
    private static final int NAME_MAX = 64;

    private static final int ALIAS_MAX = 128;

    private static final int NOTE_MAX = 512;

    private static final int GENDER_MAX = 16;

    /** 与 media_person.avatar_path VARCHAR(400) 对齐 */
    private static final int AVATAR_MAX = 400;

    /** 与 media_video_actor.role VARCHAR(32) 对齐 */
    private static final int ROLE_MAX = 32;

    /** 单个视频 / 相册一次最多绑定的人物数 */
    private static final int PERSON_IDS_MAX = 50;

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 200;

    private static final String DATE_PATTERN = "yyyy-MM-dd";

    @Autowired
    private MediaPersonMapper mediaPersonMapper;

    /** 人物写操作会改索引（videos[].persons / albums[].persons / people[]），写完必须失效快照 */
    @Autowired
    private IMediaIndexService mediaIndexService;

    // ------------------------------------------------------------------ 管理端：人物字典

    @Override
    public Map<String, Object> personPage(String q, int page, int size)
    {
        String keyword = trimToNull(q);
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        int offset = (safePage - 1) * safeSize;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MediaPerson person : mediaPersonMapper.selectPersonPage(keyword, offset, safeSize))
        {
            rows.add(personNode(person));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", mediaPersonMapper.countPersonPage(keyword));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createPerson(MediaPersonPatch patch)
    {
        if (patch == null || !patch.isNamePresent())
        {
            throw MediaException.badRequest("name 必填（去首尾空格后 1-" + NAME_MAX + " 字符）");
        }
        String name = requireName(patch.getName());
        MediaPerson existing = mediaPersonMapper.selectPersonByName(name);
        if (existing != null)
        {
            throw MediaException.conflict("人物名已存在: " + name);
        }
        MediaPerson fresh = new MediaPerson();
        fresh.setName(name);
        fresh.setAlias(limit(patch.getAlias(), ALIAS_MAX, "alias"));
        fresh.setNote(limit(patch.getNote(), NOTE_MAX, "note"));
        fresh.setGender(limit(patch.getGender(), GENDER_MAX, "gender"));
        fresh.setBirthday(patch.getBirthday());
        if (patch.isAvatarPresent())
        {
            fresh.setAvatarPath(checkAvatar(patch.getAvatarPath()));
        }
        mediaPersonMapper.insertPerson(fresh);
        invalidateIndex();
        log.info("新建媒体人物 id={} name={}", fresh.getId(), name);
        return personNode(mediaPersonMapper.selectPersonDetail(fresh.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updatePerson(Long id, MediaPersonPatch patch)
    {
        MediaPerson current = requirePerson(id);
        if (patch == null || !(patch.isNamePresent() || patch.isAliasPresent() || patch.isNotePresent()
                || patch.isGenderPresent() || patch.isBirthdayPresent() || patch.isAvatarPresent()))
        {
            throw MediaException.badRequest("body 里没有任何可改字段（name / alias / note / gender / birthday / avatarPath）");
        }

        if (patch.isNamePresent())
        {
            String name = requireName(patch.getName());
            if (!name.equals(current.getName()))
            {
                MediaPerson other = mediaPersonMapper.selectPersonByName(name);
                if (other != null && !other.getId().equals(id))
                {
                    throw MediaException.conflict("人物名已存在: " + name);
                }
                mediaPersonMapper.updatePersonName(id, name);
                log.info("媒体人物改名 id={} {} -> {}", id, current.getName(), name);
            }
        }
        if (patch.isAliasPresent())
        {
            mediaPersonMapper.updatePersonAlias(id, limit(patch.getAlias(), ALIAS_MAX, "alias"));
        }
        if (patch.isNotePresent())
        {
            mediaPersonMapper.updatePersonNote(id, limit(patch.getNote(), NOTE_MAX, "note"));
        }
        if (patch.isGenderPresent())
        {
            mediaPersonMapper.updatePersonGender(id, limit(patch.getGender(), GENDER_MAX, "gender"));
        }
        if (patch.isBirthdayPresent())
        {
            mediaPersonMapper.updatePersonBirthday(id, patch.getBirthday());
        }
        if (patch.isAvatarPresent())
        {
            mediaPersonMapper.updatePersonAvatar(id, checkAvatar(patch.getAvatarPath()));
        }
        invalidateIndex();
        return personNode(mediaPersonMapper.selectPersonDetail(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deletePerson(Long id)
    {
        requirePerson(id);
        long videoRefs = mediaPersonMapper.countVideoRefsByPerson(id);
        long albumRefs = mediaPersonMapper.countAlbumRefsByPerson(id);
        if (videoRefs > 0 || albumRefs > 0)
        {
            throw MediaException.badRequest("人物仍被引用，不能删除：videoCount=" + videoRefs
                    + ", albumCount=" + albumRefs + "（先解绑再删）");
        }
        mediaPersonMapper.deletePerson(id);
        invalidateIndex();
        log.info("删除媒体人物 id={}", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", 1);
        out.put("id", id);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAvatar(Long id, String path)
    {
        requirePerson(id);
        String clean = checkAvatar(path);
        mediaPersonMapper.updatePersonAvatar(id, clean);
        invalidateIndex();
        log.info("设置媒体人物头像 id={} path={}", id, clean);
        return personNode(mediaPersonMapper.selectPersonDetail(id));
    }

    // ------------------------------------------------------------------ 管理端：绑定

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> replaceVideoPersons(String videoId, List<Long> personIds, Map<Long, String> roles)
    {
        if (videoId == null || videoId.trim().isEmpty())
        {
            throw MediaException.badRequest("missing video id");
        }
        if (mediaPersonMapper.countVideoById(videoId) == 0)
        {
            throw MediaException.notFound("视频不存在: " + videoId);
        }
        List<Long> target = distinctIds(personIds);
        requirePersonsExist(target);
        Map<Long, String> cleanRoles = checkRoles(target, roles);

        // 旧绑定要在删除前记下来，否则 use_count 漏算「刚被换成别人」的那几个人
        Set<Long> touched = new LinkedHashSet<>(mediaPersonMapper.selectPersonIdsByVideoIds(List.of(videoId)));
        mediaPersonMapper.deleteVideoActorPersons(videoId);
        for (Long personId : target)
        {
            mediaPersonMapper.insertVideoActorPerson(videoId, personId, cleanRoles.get(personId));
            touched.add(personId);
        }
        if (!touched.isEmpty())
        {
            mediaPersonMapper.recomputePersonUseCount(new ArrayList<>(touched));
        }
        invalidateIndex();
        log.info("视频 {} 人物全量替换为 {} role={}", videoId, target, cleanRoles);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", target.size());
        out.put("id", videoId);
        out.put("persons", mediaPersonMapper.selectPersonActorsByVideoId(videoId));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> replaceAlbumPersons(String albumId, List<Long> personIds)
    {
        if (albumId == null || albumId.trim().isEmpty())
        {
            throw MediaException.badRequest("missing album id");
        }
        if (mediaPersonMapper.countAlbumById(albumId) == 0)
        {
            throw MediaException.notFound("相册不存在: " + albumId);
        }
        List<Long> target = distinctIds(personIds);
        requirePersonsExist(target);

        Set<Long> touched = new LinkedHashSet<>(mediaPersonMapper.selectPersonIdsByAlbumIds(List.of(albumId)));
        mediaPersonMapper.deleteAlbumPersons(albumId);
        for (Long personId : target)
        {
            mediaPersonMapper.insertAlbumPerson(albumId, personId);
            touched.add(personId);
        }
        // 单选镜像列：第一个人物（无人物 = NULL），老读路径（索引 people[] / admin?personId=）照旧正确
        mediaPersonMapper.updateAlbumPrimaryPerson(albumId, target.isEmpty() ? null : target.get(0));
        if (!touched.isEmpty())
        {
            mediaPersonMapper.recomputePersonUseCount(new ArrayList<>(touched));
        }
        invalidateIndex();
        log.info("相册 {} 人物全量替换为 {}", albumId, target);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", target.size());
        out.put("id", albumId);
        out.put("persons", mediaPersonMapper.selectAlbumPersons(albumId));
        return out;
    }

    // ------------------------------------------------------------------ 站端（只读）

    @Override
    public List<Map<String, Object>> personBriefList()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaPerson person : mediaPersonMapper.selectPersonBriefList())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", person.getId());
            node.put("name", person.getName());
            node.put("alias", person.getAlias());
            node.put("useCount", person.getUseCount() == null ? 0 : person.getUseCount());
            out.add(node);
        }
        return out;
    }

    @Override
    public Map<String, Object> personDetail(Long id)
    {
        return personNode(requirePerson(id));
    }

    @Override
    public Map<String, Object> personVideos(Long id, int page, int size)
    {
        requirePerson(id);
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", mediaPersonMapper.selectPersonVideoPage(id, (safePage - 1) * safeSize, safeSize));
        out.put("total", mediaPersonMapper.countPersonVideos(id));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    @Override
    public Map<String, Object> personAlbums(Long id, int page, int size)
    {
        requirePerson(id);
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? PAGE_SIZE_DEFAULT : Math.min(size, PAGE_SIZE_MAX);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", mediaPersonMapper.selectPersonAlbumPage(id, (safePage - 1) * safeSize, safeSize));
        out.put("total", mediaPersonMapper.countPersonAlbums(id));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    // ------------------------------------------------------------------ 输出 / 校验 / 工具

    private Map<String, Object> personNode(MediaPerson person)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", person.getId());
        node.put("name", person.getName());
        node.put("alias", person.getAlias());
        node.put("note", person.getNote());
        node.put("avatarPath", person.getAvatarPath());
        node.put("gender", person.getGender());
        node.put("birthday", formatDate(person.getBirthday()));
        node.put("useCount", person.getUseCount() == null ? 0 : person.getUseCount());
        node.put("videoCount", person.getVideoCount() == null ? 0L : person.getVideoCount());
        node.put("albumCount", person.getAlbumCount() == null ? 0L : person.getAlbumCount());
        return node;
    }

    private MediaPerson requirePerson(Long id)
    {
        if (id == null)
        {
            throw MediaException.badRequest("missing id");
        }
        MediaPerson person = mediaPersonMapper.selectPersonDetail(id);
        if (person == null)
        {
            throw MediaException.notFound("人物不存在: " + id);
        }
        return person;
    }

    private void requirePersonsExist(List<Long> personIds)
    {
        if (personIds.isEmpty())
        {
            return;
        }
        long found = mediaPersonMapper.countPersonsByIds(personIds);
        if (found != personIds.size())
        {
            throw MediaException.badRequest("personIds 里有不存在的人物 id（提交 " + personIds.size()
                    + " 个，实际命中 " + found + " 个）");
        }
    }

    /** 角色只能给本次提交的人物；长度 <= 32；空白视同未指定 */
    private static Map<Long, String> checkRoles(List<Long> personIds, Map<Long, String> roles)
    {
        Map<Long, String> out = new LinkedHashMap<>();
        if (roles == null || roles.isEmpty())
        {
            return out;
        }
        Set<Long> allowed = new LinkedHashSet<>(personIds);
        for (Map.Entry<Long, String> entry : roles.entrySet())
        {
            Long personId = entry.getKey();
            if (personId == null || !allowed.contains(personId))
            {
                throw MediaException.badRequest("roles 里的 personId 不在本次 personIds 中: " + personId);
            }
            String role = trimToNull(entry.getValue());
            if (role != null)
            {
                if (role.length() > ROLE_MAX)
                {
                    throw MediaException.badRequest("角色最长 " + ROLE_MAX + " 个字符: " + role);
                }
                out.put(personId, role);
            }
        }
        return out;
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
            throw MediaException.badRequest("path 最长 " + AVATAR_MAX + " 个字符");
        }
        String lower = path.toLowerCase();
        boolean ok = lower.startsWith("http://") || lower.startsWith("https://")
                || (path.startsWith("/") && !lower.startsWith("//"));
        if (!ok)
        {
            throw MediaException.badRequest("path 必须是 http(s) URL 或站内绝对路径（以 / 开头）: " + path);
        }
        return path;
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
                throw MediaException.badRequest("personIds 里含空 id");
            }
            set.add(id);
        }
        if (set.size() > PERSON_IDS_MAX)
        {
            throw MediaException.badRequest("personIds 一次最多 " + PERSON_IDS_MAX + " 个");
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
            // 索引失效失败不能回滚已经写好的人物绑定：最多让用户端晚 60s 看到
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
