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
import org.springframework.dao.DuplicateKeyException;
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

    // ---- P5-A 资料字段的长度 / 范围上限（与 doc/media_person_profile.sql 列宽对齐） ----

    private static final int MEASUREMENTS_MAX = 128;

    private static final int TATTOOS_MAX = 255;

    private static final int PIERCINGS_MAX = 255;

    private static final int ETHNICITY_MAX = 32;

    private static final int COLOR_MAX = 32;

    private static final int NATIONALITY_MAX = 64;

    private static final int SOCIAL_MAX = 128;

    private static final int HEIGHT_MIN = 100;

    private static final int HEIGHT_MAX = 250;

    private static final int WEIGHT_MIN = 30;

    private static final int WEIGHT_MAX = 200;

    private static final int CAREER_MIN = 1900;

    private static final int CAREER_MAX = 2100;

    /** 与 media_person.avatar_path VARCHAR(400) 对齐 */
    private static final int AVATAR_MAX = 400;

    // ---- P5.48 手动头像（doc/media_person_avatar.sql） ----

    /** 手动头像来源：人物名下相册里的一张图（ref = {@code "<albumId>:<seq>"}） */
    private static final String AVATAR_KIND_PHOTO = "photo";

    /** 手动头像来源：外部图片地址（ref = http(s) / data:image） */
    private static final String AVATAR_KIND_URL = "url";

    /** 与 media_person.avatar_ref VARCHAR(512) 对齐 */
    private static final int AVATAR_REF_MAX = 512;

    /** 与 media_album.id VARCHAR(16) 对齐 */
    private static final int ALBUM_ID_MAX = 16;

    /** 缩略图 URL 前缀：与前端 useMediaApi.photoThumbUrl 同一套规则（/api/media/thumb/p/<albumId>/<pad4(seq)>） */
    private static final String AVATAR_THUMB_PREFIX = "/api/media/thumb/p/";

    /** 与 media_video_actor.role VARCHAR(32) 对齐 */
    private static final int ROLE_MAX = 32;

    /** 单个视频 / 相册一次最多绑定的人物数 */
    private static final int PERSON_IDS_MAX = 50;

    /**
     * 「素人」人物名（无演员视频的承载体，真实存在于 media_person）
     *
     * <p>语义：{@code 素人} 与任何真实演员互斥。见
     * {@code doc/media_amateur_person.sql} 与 {@link #replaceVideoPersons(String, List, Map)}。</p>
     */
    private static final String AMATEUR_NAME = "素人";

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
        applyProfile(fresh, patch);
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
                || patch.isGenderPresent() || patch.isBirthdayPresent() || patch.isAvatarPresent()
                || patch.isProfilePresent()))
        {
            throw MediaException.badRequest("body 里没有任何可改字段（name / alias / note / gender / birthday / avatarPath"
                    + " / measurements / careerStart / careerEnd / tattoos / piercings / ethnicity / hairColor"
                    + " / eyeColor / heightCm / weightKg / fakeBoobs / nationality / twitter / instagram）");
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
        if (patch.isProfilePresent())
        {
            // 未出现在 body 里的资料字段沿用库里的现值，出现的（含 null）覆盖成新值，
            // 然后一次 UPDATE 写全 14 列：不会出现「只写了一半字段」的半成品行
            MediaPerson target = profileOf(current);
            applyProfile(target, patch);
            mediaPersonMapper.updatePersonProfile(id, target);
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

    // ------------------------------------------------------------------ 站端：手动头像（P5.48）

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setAvatar(Long id, String kind, String ref)
    {
        requirePerson(id);
        String cleanKind = checkAvatarKind(kind);
        String cleanRef = checkAvatarRef(cleanKind, ref);
        if (AVATAR_KIND_PHOTO.equals(cleanKind))
        {
            // 「从该人物名下的图片里挑」要在服务端兜住：前端给的 albumId 是可见值，
            // 但匿名接口不能相信它 —— 换成别人相册里的一张图，头像就挂上去了。
            int colon = cleanRef.indexOf(':');
            String albumId = cleanRef.substring(0, colon);
            int seq = Integer.parseInt(cleanRef.substring(colon + 1));
            if (mediaPersonMapper.countPersonPhotoOwned(id, albumId, seq) == 0)
            {
                throw MediaException.badRequest("该图片不在这个人物名下: " + cleanRef);
            }
        }
        mediaPersonMapper.updatePersonAvatarRef(id, cleanKind, cleanRef);
        invalidateIndex();
        log.info("设置人物手动头像 id={} kind={} ref={}", id, cleanKind, cleanRef);
        return personNode(mediaPersonMapper.selectPersonDetail(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> clearAvatar(Long id)
    {
        requirePerson(id);
        mediaPersonMapper.updatePersonAvatarRef(id, null, null);
        invalidateIndex();
        log.info("清除人物手动头像 id={}（回退三级自动）", id);
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

        // 「素人」占位规则（P5.3-A）：有真实演员就没有素人；空 / 全被剔除 → 落素人。
        // 必须排在 checkRoles 之前：因规则被剔除的素人 id，其 roles 键也要一起丢掉，
        // 否则「只提交 {主演素人: 群演}」这种 payload 会因为键不在 personIds 里而 400。
        Set<Long> dropped = new LinkedHashSet<>();
        Map<Long, String> submittedRoles = roles;
        Long amateurId = resolveAmateurId();
        if (amateurId != null)
        {
            if (target.isEmpty())
            {
                // 解绑全部 = 回到素人（而不是变成「一个演员都没有」）：站端「清理演员」的语义
                // 就是「这是一部没有署名的片子」，用一个真实的素人行承载才不会在人物页里消失
                target = new ArrayList<>();
                target.add(amateurId);
            }
            else if (target.size() > 1 && target.contains(amateurId))
            {
                // 多人物出演：素人与真实演员互斥，真实演员优先
                target = new ArrayList<>(target);
                target.remove(amateurId);
                dropped.add(amateurId);
            }
        }
        if (!dropped.isEmpty() && submittedRoles != null)
        {
            submittedRoles = new LinkedHashMap<>(submittedRoles);
            submittedRoles.keySet().removeAll(dropped);
        }
        Map<Long, String> cleanRoles = checkRoles(target, submittedRoles);

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
        log.info("视频 {} 人物全量替换为 {} role={} 剔除素人={}", videoId, target, cleanRoles, dropped);

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

    // ------------------------------------------------------------------ 站端（写）

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensurePerson(String name, String alias)
    {
        // 名字校验 / 写入与 createPerson 共用同一段（requireName + insertPerson），
        // 只有「撞名怎么办」不一样：这里复用，那里 409
        String cleanName = requireName(name);
        MediaPerson person = mediaPersonMapper.selectPersonByName(cleanName);
        boolean created = false;
        if (person == null)
        {
            MediaPerson fresh = new MediaPerson();
            fresh.setName(cleanName);
            fresh.setAlias(limit(alias, ALIAS_MAX, "alias"));
            try
            {
                mediaPersonMapper.insertPerson(fresh);
            }
            catch (DuplicateKeyException e)
            {
                // uk_person_name 兜住并发同名：两个请求同时到，一个插入成功、另一个回滚成复用
                person = mediaPersonMapper.selectPersonByName(cleanName);
                if (person == null)
                {
                    throw e;
                }
                log.info("站端新建人物并发撞名，复用既有 id={} name={}", person.getId(), cleanName);
                return personCreatedNode(person, false);
            }
            person = mediaPersonMapper.selectPersonDetail(fresh.getId());
            created = true;
            invalidateIndex();
            log.info("站端新建人物 id={} name={}", fresh.getId(), cleanName);
        }
        return personCreatedNode(person, created);
    }

    /** 站端新建人物的最小回包：{@code {id,name,created}}（不带整行，前端只要 id 去绑定） */
    private static Map<String, Object> personCreatedNode(MediaPerson person, boolean created)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", person.getId());
        out.put("name", person.getName());
        out.put("created", created);
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
            // P5.48：人物墙首屏就要头像，清单里直接把解析好的 URL 带上（否则 N 个人物 N 个详情请求）
            node.put("avatarKind", person.getAvatarKind());
            node.put("avatarRef", person.getAvatarRef());
            node.put("avatarUrl", avatarUrlOf(person.getAvatarKind(), person.getAvatarRef(), person.getAvatarPath()));
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
        // P5.48：手动头像三件套 —— kind / ref 是「存了什么」，avatarUrl 是「后端解析好的最终地址」
        node.put("avatarKind", person.getAvatarKind());
        node.put("avatarRef", person.getAvatarRef());
        node.put("avatarUrl", avatarUrlOf(person.getAvatarKind(), person.getAvatarRef(), person.getAvatarPath()));
        node.put("gender", person.getGender());
        node.put("birthday", formatDate(person.getBirthday()));
        node.put("useCount", person.getUseCount() == null ? 0 : person.getUseCount());
        node.put("videoCount", person.getVideoCount() == null ? 0L : person.getVideoCount());
        node.put("albumCount", person.getAlbumCount() == null ? 0L : person.getAlbumCount());
        // P5-A 资料字段：空值不输出（库里有 13 个人物、绝大多数没填这些字段，
        // 全量回 null 只会让站端 payload 变胖、前端还得逐个判空）
        putIfNotEmpty(node, "measurements", person.getMeasurements());
        putIfNotEmpty(node, "careerStart", person.getCareerStart());
        putIfNotEmpty(node, "careerEnd", person.getCareerEnd());
        putIfNotEmpty(node, "tattoos", person.getTattoos());
        putIfNotEmpty(node, "piercings", person.getPiercings());
        putIfNotEmpty(node, "ethnicity", person.getEthnicity());
        putIfNotEmpty(node, "hairColor", person.getHairColor());
        putIfNotEmpty(node, "eyeColor", person.getEyeColor());
        putIfNotEmpty(node, "heightCm", person.getHeightCm());
        putIfNotEmpty(node, "weightKg", person.getWeightKg());
        putIfNotEmpty(node, "fakeBoobs", person.getFakeBoobs());
        putIfNotEmpty(node, "nationality", person.getNationality());
        putIfNotEmpty(node, "twitter", person.getTwitter());
        putIfNotEmpty(node, "instagram", person.getInstagram());
        return node;
    }

    /** 只在有值时才写键（null / 空白字符串都算「未填写」） */
    private static void putIfNotEmpty(Map<String, Object> node, String key, Object value)
    {
        if (value == null)
        {
            return;
        }
        if (value instanceof String text && text.trim().isEmpty())
        {
            return;
        }
        node.put(key, value);
    }

    /**
     * 把库里现存的 14 个资料字段复制进一个临时对象（更新时作为「未出现字段」的兜底值）
     *
     * <p>只复制资料字段，不复制 name / alias 等既有列：它们各自有单列 UPDATE，
     * 混在一起写会让「只改别名」也去覆盖 name，平白多一次重名判断。</p>
     */
    private static MediaPerson profileOf(MediaPerson source)
    {
        MediaPerson target = new MediaPerson();
        target.setMeasurements(source.getMeasurements());
        target.setCareerStart(source.getCareerStart());
        target.setCareerEnd(source.getCareerEnd());
        target.setTattoos(source.getTattoos());
        target.setPiercings(source.getPiercings());
        target.setEthnicity(source.getEthnicity());
        target.setHairColor(source.getHairColor());
        target.setEyeColor(source.getEyeColor());
        target.setHeightCm(source.getHeightCm());
        target.setWeightKg(source.getWeightKg());
        target.setFakeBoobs(source.getFakeBoobs());
        target.setNationality(source.getNationality());
        target.setTwitter(source.getTwitter());
        target.setInstagram(source.getInstagram());
        return target;
    }

    /**
     * 把补丁里的资料字段写进目标对象并校验（新建与更新共用一段实现）
     *
     * <p>校验顺序：长度上限（与列宽对齐）→ 数值范围（身高 100-250 / 体重 30-200 /
     * 年份 1900-2100）→ 跨字段（careerEnd >= careerStart）。跨字段比较用的是「合并后的有效值」，
     * 因此只提交 careerEnd 时也会拿库里的 careerStart 做比较。</p>
     */
    private static void applyProfile(MediaPerson target, MediaPersonPatch patch)
    {
        if (patch.isMeasurementsPresent())
        {
            target.setMeasurements(limit(patch.getMeasurements(), MEASUREMENTS_MAX, "measurements"));
        }
        if (patch.isCareerStartPresent())
        {
            target.setCareerStart(checkRange(patch.getCareerStart(), CAREER_MIN, CAREER_MAX, "careerStart"));
        }
        if (patch.isCareerEndPresent())
        {
            target.setCareerEnd(checkRange(patch.getCareerEnd(), CAREER_MIN, CAREER_MAX, "careerEnd"));
        }
        if (patch.isTattoosPresent())
        {
            target.setTattoos(limit(patch.getTattoos(), TATTOOS_MAX, "tattoos"));
        }
        if (patch.isPiercingsPresent())
        {
            target.setPiercings(limit(patch.getPiercings(), PIERCINGS_MAX, "piercings"));
        }
        if (patch.isEthnicityPresent())
        {
            target.setEthnicity(limit(patch.getEthnicity(), ETHNICITY_MAX, "ethnicity"));
        }
        if (patch.isHairColorPresent())
        {
            target.setHairColor(limit(patch.getHairColor(), COLOR_MAX, "hairColor"));
        }
        if (patch.isEyeColorPresent())
        {
            target.setEyeColor(limit(patch.getEyeColor(), COLOR_MAX, "eyeColor"));
        }
        if (patch.isHeightCmPresent())
        {
            target.setHeightCm(checkRange(patch.getHeightCm(), HEIGHT_MIN, HEIGHT_MAX, "heightCm"));
        }
        if (patch.isWeightKgPresent())
        {
            target.setWeightKg(checkRange(patch.getWeightKg(), WEIGHT_MIN, WEIGHT_MAX, "weightKg"));
        }
        if (patch.isFakeBoobsPresent())
        {
            target.setFakeBoobs(patch.getFakeBoobs());
        }
        if (patch.isNationalityPresent())
        {
            target.setNationality(limit(patch.getNationality(), NATIONALITY_MAX, "nationality"));
        }
        if (patch.isTwitterPresent())
        {
            target.setTwitter(limit(patch.getTwitter(), SOCIAL_MAX, "twitter"));
        }
        if (patch.isInstagramPresent())
        {
            target.setInstagram(limit(patch.getInstagram(), SOCIAL_MAX, "instagram"));
        }
        checkCareer(target.getCareerStart(), target.getCareerEnd());
    }

    /** 数值范围：null = 清空，合法；越界 400 */
    private static Integer checkRange(Integer value, int min, int max, String field)
    {
        if (value == null)
        {
            return null;
        }
        if (value < min || value > max)
        {
            throw MediaException.badRequest(field + " 必须在 " + min + "-" + max + " 之间（null 表示清空）");
        }
        return value;
    }

    /** 出道年份 <= 退役年份；任一侧为空时只校验另一侧（各自的范围已在 checkRange 里校验过） */
    private static void checkCareer(Integer start, Integer end)
    {
        if (start != null && end != null && end < start)
        {
            throw MediaException.badRequest("careerEnd 必须 >= careerStart（" + end + " < " + start + "）");
        }
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

    /**
     * 素人 id（{@code media_person.name = '素人'} 那一行，由 {@code doc/media_amateur_person.sql} 保证存在）
     *
     * <p>返回 {@code null} 表示库里还没有素人（迁移没执行过 / 被人删了）：此时占位规则整体跳过，
     * 写入行为退回 P3-A 的原始语义（空表 = 无人物）。这样「没跑迁移的老库」不会因为这个规则
     * 突然多出一条 {@code person_id} 指向不存在 id 的绑定行。</p>
     *
     * <p>按名字查而不是硬编码 id：素人行的 id 由自增决定，不同实例各不相同；查询走
     * {@code uk_person_name} 唯一索引，每次视频写入多一次主键查询，可忽略。</p>
     */
    private Long resolveAmateurId()
    {
        MediaPerson amateur = mediaPersonMapper.selectPersonByName(AMATEUR_NAME);
        return amateur == null ? null : amateur.getId();
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

    // ------------------------------------------------------------------ P5.48 手动头像：校验 / 解析

    /** 只接受 photo / url（大小写不敏感）；其它一律 400 —— 不猜、不静默改写成 url */
    private static String checkAvatarKind(String raw)
    {
        String kind = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!AVATAR_KIND_PHOTO.equals(kind) && !AVATAR_KIND_URL.equals(kind))
        {
            throw MediaException.badRequest("kind 只接受 " + AVATAR_KIND_PHOTO + " / " + AVATAR_KIND_URL
                    + "，本次: " + (raw == null ? "null" : raw));
        }
        return kind;
    }

    /**
     * 归一 avatar ref（photo → {@code "<albumId>:<seq>"}，url → 原串）
     *
     * <p>photo 型这次只做<b>格式</b>校验（albumId 1-16 字符、seq 非负整数），
     * 「这张图在不在该人物名下」由调用方查库确认 —— 那是业务判断，不是格式判断。</p>
     */
    private static String checkAvatarRef(String kind, String raw)
    {
        String ref = raw == null ? "" : raw.trim();
        if (ref.isEmpty())
        {
            throw MediaException.badRequest("ref 必填（清除手动头像请用 DELETE /api/media/person/{id}/avatar）");
        }
        if (AVATAR_KIND_URL.equals(kind))
        {
            String lower = ref.toLowerCase(java.util.Locale.ROOT);
            // 只放 http / https / data:image：javascript: 与 file: 会被 <img src> 或后续展示场景当真，
            // 一个匿名写接口不该把它存进库再原样吐给所有人。
            boolean ok = lower.startsWith("http://") || lower.startsWith("https://")
                    || lower.startsWith("data:image/");
            if (!ok)
            {
                throw MediaException.badRequest("ref 只接受 http:// / https:// / data:image/ 开头的地址: " + ref);
            }
            if (ref.length() > AVATAR_REF_MAX)
            {
                throw MediaException.badRequest("ref 最长 " + AVATAR_REF_MAX + " 个字符（data: 内联图片请改用图片地址）");
            }
            return ref;
        }
        int colon = ref.indexOf(':');
        if (colon <= 0 || colon == ref.length() - 1)
        {
            throw MediaException.badRequest("photo 型 ref 格式为 <albumId>:<seq>（如 a164:0），本次: " + ref);
        }
        String albumId = ref.substring(0, colon).trim();
        if (albumId.isEmpty() || albumId.length() > ALBUM_ID_MAX)
        {
            throw MediaException.badRequest("ref 里的 albumId 长度必须是 1-" + ALBUM_ID_MAX + ": " + albumId);
        }
        int seq;
        try
        {
            seq = Integer.parseInt(ref.substring(colon + 1).trim());
        }
        catch (NumberFormatException e)
        {
            throw MediaException.badRequest("ref 里的 seq 必须是整数: " + ref);
        }
        if (seq < 0)
        {
            throw MediaException.badRequest("ref 里的 seq 不能为负: " + ref);
        }
        return albumId + ":" + seq;
    }

    /**
     * 解析「最终头像 URL」——<b>后端解析，前端不拼</b>（P5.48 契约）
     *
     * <p>顺序：手动（avatar_kind + avatar_ref）→ 历史 avatar_path → {@code null}。
     * {@code null} 的语义是「没有显式头像」，前端继续三级自动回退（相册首图 → 视频封面 → 首字占位）。
     * 库里若有脏值（kind 非法 / photo 型 ref 不成形），这里一律当「没设手动头像」处理，
     * 不抛异常 —— 一个坏行不该让整个人物列表读不出来。</p>
     */
    private static String avatarUrlOf(String kind, String ref, String avatarPath)
    {
        String cleanKind = kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        String cleanRef = ref == null ? "" : ref.trim();
        if (!cleanRef.isEmpty())
        {
            if (AVATAR_KIND_URL.equals(cleanKind))
            {
                return cleanRef;
            }
            if (AVATAR_KIND_PHOTO.equals(cleanKind))
            {
                int colon = cleanRef.indexOf(':');
                if (colon > 0 && colon < cleanRef.length() - 1)
                {
                    String albumId = cleanRef.substring(0, colon).trim();
                    try
                    {
                        int seq = Integer.parseInt(cleanRef.substring(colon + 1).trim());
                        if (!albumId.isEmpty() && seq >= 0)
                        {
                            return photoThumbUrl(albumId, seq);
                        }
                    }
                    catch (NumberFormatException ignored)
                    {
                        // 脏行：当没设手动头像，落到 avatar_path
                    }
                }
            }
        }
        return siteUrl(avatarPath);
    }

    /** 站内缩略图 URL：{@code /api/media/thumb/p/<albumId>/<pad4(seq)>}（与前端 photoThumbUrl 完全同一条规则） */
    private static String photoThumbUrl(String albumId, int seq)
    {
        StringBuilder url = new StringBuilder(AVATAR_THUMB_PREFIX).append(albumId).append('/');
        String digits = String.valueOf(seq);
        for (int i = digits.length(); i < 4; i++)
        {
            url.append('0');
        }
        return url.append(digits).toString();
    }

    /** 历史 avatar_path 归一：http(s) / '//' / 已带 '/' 原样用，其余当成站点根相对（与前端 normalizeAvatarPath 同规则） */
    private static String siteUrl(String raw)
    {
        String path = trimToNull(raw);
        if (path == null)
        {
            return null;
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || path.startsWith("//")
                || path.startsWith("/"))
        {
            return path;
        }
        return "/" + path;
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
