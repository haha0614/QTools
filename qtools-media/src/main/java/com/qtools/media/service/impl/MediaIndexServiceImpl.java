package com.qtools.media.service.impl;

import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaAlbum;
import com.qtools.media.domain.MediaIndexSnapshot;
import com.qtools.media.domain.MediaPerson;
import com.qtools.media.domain.MediaPhoto;
import com.qtools.media.domain.MediaTag;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.domain.MediaVideoActor;
import com.qtools.media.domain.MediaVideoMarker;
import com.qtools.media.domain.MediaVideoTag;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaAlbumMapper;
import com.qtools.media.mapper.MediaOrganizeMapper;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.mapper.MediaStudioMapper;
import com.qtools.media.mapper.MediaPhotoMapper;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaGuestAccessService;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.support.MediaRating;

/**
 * 媒体索引聚合 服务实现
 *
 * <p>输出结构对齐 Node 版 data/index.json：{built, root, stats, facetOrder, videos, albums, people}，
 * 字段名统一 camelCase，便于前端平移。ETag 由序列化后的字节体摘要生成，因此数据不变时
 * ETag 稳定，If-None-Match 可以命中 304。</p>
 *
 * @author qtools
 */
@Service
public class MediaIndexServiceImpl implements IMediaIndexService
{
    private static final Logger log = LoggerFactory.getLogger(MediaIndexServiceImpl.class);

    /** 索引缓存时长（毫秒） */
    private static final long CACHE_TTL_MS = 60_000L;

    /** facet 展示顺序，与 index.json 的 facetOrder 保持一致 */
    private static final List<String> FACET_ORDER = List.of("分类", "类型", "题材", "行为", "角色", "场景",
            "穿着", "审查", "质量", "部位", "动作", "演员");

    private static final String BUILT_PATTERN = "yyyy年M月d日 HH:mm:ss";

    /**
     * 字典标签在 media_video_tag 里固定的 facet 值（M3-A）
     *
     * <p>管理端打标签写的就是这一行，因此索引里「成对 tags」天然带上它；
     * 另外单独回一份 {@code dictTags}（纯名称数组）给用户端，省得前端自己拆对。</p>
     */
    private static final String FACET_TAG = "标签";

    /** 内置 ObjectMapper：不依赖 MVC 的序列化配置，保证 ETag 计算与响应体完全一致 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MediaVideoMapper mediaVideoMapper;

    @Autowired
    private MediaAlbumMapper mediaAlbumMapper;

    @Autowired
    private MediaPhotoMapper mediaPhotoMapper;

    @Autowired
    private MediaPersonMapper mediaPersonMapper;

    /** 工作室（P5.50）：索引 videos[].studios 与站端元信息共用「视频 → 工作室节点」查询 */
    @Autowired
    private MediaStudioMapper mediaStudioMapper;

    /** 组织属性只读（P5.50）：站端匿名元信息要读字典标签与时间点标记（标记没有任何索引侧副本） */
    @Autowired
    private MediaOrganizeMapper mediaOrganizeMapper;

    @Autowired
    private MediaProperties mediaProperties;

    /** 派生资源（sprite / pv2）目录清单：索引批量判断 hasSprite / hasPreview 用 */
    @Autowired
    private IMediaFileService mediaFileService;

    /** 未登录可见性（P5.78）：{@code guest_visible = 0} 的根下内容对未登录访客整行筛掉 */
    @Autowired
    private IMediaGuestAccessService mediaGuestAccessService;

    /** 卡片背景预热（P5.92）：索引重建成功后在后台把人物墙那 13 张图渲好 */
    @Autowired
    private com.qtools.media.service.IMediaPersonCardBgService mediaPersonCardBgService;

    /**
     * 快照缓存：{@code includeMissing} × {@code guest} 四种组合各存一份
     *
     * <p>P5.78 起键里必须有 {@code guest}：未登录访客看到的是筛过的索引，TTL 与 ETag 都必须
     * 与登录态那份分开，否则登录用户会把「含收紧库根内容」的 304 递给未登录的人（或反过来）。</p>
     */
    private final Map<String, MediaIndexSnapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public MediaIndexSnapshot indexSnapshot()
    {
        return indexSnapshot(true);
    }

    @Override
    public MediaIndexSnapshot indexSnapshot(boolean includeMissing)
    {
        // 不带登录态参数的调用一律按「已登录 / 不筛」处理：老调用方的行为逐字不变
        return indexSnapshot(includeMissing, false);
    }

    @Override
    public MediaIndexSnapshot indexSnapshot(boolean includeMissing, boolean guest)
    {
        String key = cacheKey(includeMissing, guest);
        MediaIndexSnapshot cached = snapshots.get(key);
        if (cached != null && System.currentTimeMillis() - cached.getBuiltAt() < CACHE_TTL_MS)
        {
            return cached;
        }
        synchronized (this)
        {
            cached = snapshots.get(key);
            if (cached != null && System.currentTimeMillis() - cached.getBuiltAt() < CACHE_TTL_MS)
            {
                return cached;
            }
            long start = System.currentTimeMillis();
            Map<String, Object> data = buildIndex(includeMissing, guest);
            byte[] body;
            try
            {
                body = objectMapper.writeValueAsBytes(data);
            }
            catch (Exception e)
            {
                throw new MediaException(500, "索引序列化失败");
            }
            MediaIndexSnapshot fresh = new MediaIndexSnapshot(body, "\"idx-" + digest(body) + "\"",
                    System.currentTimeMillis());
            snapshots.put(key, fresh);
            log.info("媒体索引重建完成 includeMissing={} guest={} size={}B cost={}ms etag={}", includeMissing, guest,
                    body.length, System.currentTimeMillis() - start, fresh.getEtag());
            warmCardBg();
            return fresh;
        }
    }

    /**
     * P5.92：索引重建成功 → 后台预热人物卡背景
     *
     * <p>为什么挂在索引上：人物页一进来就请求 {@code /media/index}（60s 一份快照），而索引重建
     * 恰好是「人物数据刚变过」的时间点（新建人物 / 换头像 / 保存背景调整都会让索引失效）。
     * 在这里排一次预热，用户真的打开人物页时那 13 张卡就是 HIT（3–20ms）而不是并发冷渲染（180–380ms）。</p>
     *
     * <p>guest / 非 guest 都预热：站端人物页是<b>匿名</b>访问（guest=true 的那份快照），
     * 可见性筛的是库根内容，不是人物 —— 两种快照要的是同一批卡片背景图。</p>
     */
    private void warmCardBg()
    {
        if (mediaPersonCardBgService == null)
        {
            return;
        }
        try
        {
            List<Long> ids = new ArrayList<>();
            for (MediaPerson person : mediaPersonMapper.selectPersonList())
            {
                if (person != null && person.getId() != null)
                {
                    ids.add(person.getId());
                }
            }
            mediaPersonCardBgService.warmAsync(ids);
        }
        catch (RuntimeException e)
        {
            log.debug("索引重建后预热卡片背景失败（不影响索引）: {}", e.getMessage());
        }
    }

    @Override
    public Map<String, Object> selectVideoDetail(String id)
    {
        MediaVideo video = mediaVideoMapper.selectVideoById(id);
        if (video == null)
        {
            throw MediaException.notFound();
        }
        List<String[]> tags = new ArrayList<>();
        for (MediaVideoTag tag : mediaVideoMapper.selectTagListByVideoId(id))
        {
            tags.add(new String[] { tag.getFacet(), tag.getName() });
        }
        List<String> actors = new ArrayList<>();
        for (MediaVideoActor actor : mediaVideoMapper.selectActorListByVideoId(id))
        {
            actors.add(actor.getName());
        }
        return toVideoNode(video, tags, actors, videoPersons(id),
                studioNodes(mediaStudioMapper.selectStudioNodesByVideoId(id)));
    }

    /**
     * 站端匿名读视频元信息（P5.50）：{@code {id,rating,tags,persons,studios,markers,fps,dur}}
     *
     * <p>5 条查询全部走主键 / {@code video_id} 索引（video 行、字典标签、标记、人物绑定、工作室绑定），
     * 没有全表扫描也没有逐条反查。标记是<b>可变数据</b>，所以这里刻意不读索引快照缓存：
     * 站端刚打完点就得能看到它，而快照有 60s TTL。写入路径本来就会 {@code invalidateSnapshot()}，
     * 元信息这条实时读与它口径一致（索引缓存只服务 /media/index 那种大对象）。</p>
     *
     * <p>数组字段（tags / persons / studios / markers）<b>恒输出</b>（无内容就是空数组），
     * 便于站端一次性拿齐形状；{@code fps} / {@code dur} 为 null 时整键不输出（老库未探测）；
     * {@code rating} 恒输出，{@code null} = 未评分（与 {@code PUT /media/video/{id}/meta} 同形）。</p>
     */
    @Override
    public Map<String, Object> selectVideoMeta(String id)
    {
        String videoId = StringUtils.isEmpty(id) ? null : id.trim();
        if (videoId == null)
        {
            throw MediaException.notFound();
        }
        // selectVideoById 自带 hidden = 0：软删（hidden=1）与不存在同口径 → 404
        MediaVideo video = mediaVideoMapper.selectVideoById(videoId);
        if (video == null)
        {
            throw MediaException.notFound();
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", video.getId());
        node.put("rating", video.getRating());

        List<Map<String, Object>> tags = new ArrayList<>();
        for (MediaTag tag : mediaOrganizeMapper.selectTagsByVideoId(videoId))
        {
            Map<String, Object> tagNode = new LinkedHashMap<>();
            tagNode.put("id", tag.getId());
            tagNode.put("name", tag.getName());
            tagNode.put("color", tag.getColor());
            tags.add(tagNode);
        }
        node.put("tags", tags);

        List<Map<String, Object>> persons = new ArrayList<>();
        for (Map<String, Object> row : mediaPersonMapper.selectPersonActorsByVideoId(videoId))
        {
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("id", row.get("personId"));
            person.put("name", row.get("name"));
            String role = stringOf(row.get("role"));
            if (role != null)
            {
                person.put("role", role);
            }
            persons.add(person);
        }
        node.put("persons", persons);

        List<Map<String, Object>> studios = studioNodes(mediaStudioMapper.selectStudioNodesByVideoId(videoId));
        node.put("studios", studios == null ? List.of() : studios);

        List<Map<String, Object>> markers = new ArrayList<>();
        for (MediaVideoMarker marker : mediaOrganizeMapper.selectMarkersByVideoId(videoId))
        {
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("id", marker.getId());
            mark.put("pos", marker.getPos());
            // 单点标记回 null（不省略键）：站端据此区分「点」与「区间」，与管理端 meta 完全一致
            mark.put("endPos", marker.getEndPos());
            mark.put("label", marker.getLabel());
            mark.put("color", marker.getColor());
            markers.add(mark);
        }
        node.put("markers", markers);

        if (video.getFps() != null)
        {
            node.put("fps", video.getFps());
        }
        if (video.getDur() != null)
        {
            node.put("dur", video.getDur());
        }
        return node;
    }

    @Override
    public Map<String, Object> selectAlbumDetail(String id)
    {
        return selectAlbumDetail(id, true);
    }

    @Override
    public Map<String, Object> selectAlbumDetail(String id, boolean includeMissing)
    {
        MediaAlbum album = mediaAlbumMapper.selectAlbumById(id);
        if (album == null)
        {
            throw MediaException.notFound();
        }
        Map<Long, String> personNames = loadPersonNames();
        List<Map<String, Object>> photos = new ArrayList<>();
        for (MediaPhoto photo : mediaPhotoMapper.selectPhotoListByAlbumId(id, Boolean.valueOf(includeMissing)))
        {
            photos.add(toPhotoNode(photo));
        }
        return toAlbumNode(album, personNames, photos, albumPersonNames(id));
    }

    @Override
    public void invalidateSnapshot()
    {
        snapshots.clear();
    }

    // ------------------------------------------------------------------ 可见性 / 缓存键

    /** 快照缓存键：{@code m<includeMissing>g<guest>}（两维四组合） */
    private static String cacheKey(boolean includeMissing, boolean guest)
    {
        return (includeMissing ? "m1" : "m0") + (guest ? "g1" : "g0");
    }

    /** 该行是否落在「仅登录可见」的根下（root_id 为 NULL 的存量行走配置根，不在任何行下 → 不筛） */
    private static boolean isHiddenRoot(Integer rootId, Set<Integer> hidden)
    {
        return rootId != null && !hidden.isEmpty() && hidden.contains(rootId);
    }

    /** P5.99：该行所属的内容分级是否对未登录访客隐藏（脏值按 general 处理，与访客判定同口径） */
    private static boolean isHiddenRating(String rating, Set<String> hiddenRatings)
    {
        return !hiddenRatings.isEmpty() && hiddenRatings.contains(MediaRating.normalize(rating));
    }

    // ------------------------------------------------------------------ 构建

    /**
     * 构建索引数据
     *
     * @param includeMissing 是否带出缺失行
     * @param guest          未登录访客：{@code guest_visible = 0} 的根下内容整行不出现（P5.78）
     *
     * <p><b>筛除口径（全站唯一，与拦截器一致）</b>：视频 / 图片按自己那行的 {@code root_id} 判；
     * 相册自己没有 {@code root_id}，按它的图片算——<b>有一张落在收紧的根下，整册对未登录访客隐藏</b>
     * （保守方向：宁可多藏不漏）。人物节点由相册聚合而来，相册被筛掉后计数自然下降；
     * 一个在收紧根下「只剩人物没有可见相册」的节点对访客整条不输出。</p>
     */
    private Map<String, Object> buildIndex(boolean includeMissing, boolean guest)
    {
        Boolean keepMissing = Boolean.valueOf(includeMissing);
        Set<Integer> hidden = guest ? mediaGuestAccessService.hiddenRootIds() : Set.of();
        Set<String> hiddenRatings = guest ? mediaGuestAccessService.hiddenRatings() : Set.of();
        List<MediaVideo> videoRows = mediaVideoMapper.selectVideoList(keepMissing);
        List<MediaVideo> videos = videoRows;
        if (!hidden.isEmpty() || !hiddenRatings.isEmpty())
        {
            videos = new ArrayList<>(videoRows.size());
            for (MediaVideo row : videoRows)
            {
                if (!isHiddenRoot(row.getRootId(), hidden) && !isHiddenRating(row.getContentRating(), hiddenRatings))
                {
                    videos.add(row);
                }
            }
            log.debug("索引未登录筛除：视频 {}/{} 可见", videos.size(), videoRows.size());
        }
        Map<String, List<String[]>> tagsByVideo = new HashMap<>();
        Set<String> facets = new LinkedHashSet<>();
        for (MediaVideoTag tag : mediaVideoMapper.selectTagList())
        {
            tagsByVideo.computeIfAbsent(tag.getVideoId(), k -> new ArrayList<>())
                    .add(new String[] { tag.getFacet(), tag.getName() });
            facets.add(tag.getFacet());
        }
        Map<String, List<String>> actorsByVideo = new HashMap<>();
        for (MediaVideoActor actor : mediaVideoMapper.selectActorList())
        {
            actorsByVideo.computeIfAbsent(actor.getVideoId(), k -> new ArrayList<>()).add(actor.getName());
        }
        // 人物（P3-A）：一次取全部绑定，逐条挂到 videos[]；为此多两条轻查询，
        // 但索引里「成对 persons + role」比让前端逐个 /person/{id} 反查便宜得多
        Map<String, List<Map<String, Object>>> personsByVideo = new HashMap<>();
        for (Map<String, Object> row : mediaPersonMapper.selectVideoPersonList())
        {
            String videoId = stringOf(row.get("videoId"));
            if (videoId == null)
            {
                continue;
            }
            personsByVideo.computeIfAbsent(videoId, k -> new ArrayList<>()).add(personNode(row));
        }
        // 工作室（P5.50 起输出 {id,name} 节点，P5-A 只给名称数组）：一次取全部「视频 / 工作室」对，
        // 逐条挂到 videos[]；hidden 的工作室不输出。仍是**一条**整表查询，没有 N+1。
        Map<String, List<Map<String, Object>>> studiosByVideo = new HashMap<>();
        for (Map<String, Object> row : mediaStudioMapper.selectVideoStudioList())
        {
            String videoId = stringOf(row.get("videoId"));
            Map<String, Object> studio = studioNode(row);
            if (videoId == null || studio == null)
            {
                continue;
            }
            studiosByVideo.computeIfAbsent(videoId, k -> new ArrayList<>()).add(studio);
        }
        Map<String, List<String>> personNamesByAlbum = new HashMap<>();
        for (Map<String, Object> row : mediaPersonMapper.selectAlbumPersonList())
        {
            String albumId = stringOf(row.get("albumId"));
            if (albumId == null)
            {
                continue;
            }
            List<String> names = personNamesByAlbum.computeIfAbsent(albumId, k -> new ArrayList<>());
            String name = stringOf(row.get("name"));
            if (name != null && !names.contains(name))
            {
                names.add(name);
            }
        }

        // 派生资源存在性：一次目录扫描成集合，逐条判断不再 stat（万级视频下差别是一个量级）
        Set<String> spriteKeys = mediaFileService.spriteKeys();
        Set<String> previewKeys = mediaFileService.previewKeys();

        // P5.45：人物节点补「最近更新」用的人-时间表 —— 视频按**人名**、相册按**person_id** 收集 mtime，
        // 因为 videos[].persons 里只有 name（索引侧从不输出 id 之外的人物列），而 albums[].personId 是现成的。
        // 两张表都只保留最大值；不新增任何 SQL（videos / albums 本来就已经整表读进来了）。
        Map<String, Date> videoMtimeByPerson = new HashMap<>();
        Map<Long, Date> albumMtimeByPerson = new HashMap<>();

        List<Map<String, Object>> videoNodes = new ArrayList<>(videos.size());
        // P5.99：人名 → 肉眼可见的视频条数。人物节点现在按「可见相册数 + 可见视频数」判定存在与否
        //（只按相册算的话，一个名下只有视频、相册全被收紧的人物会在未登录时整条消失）。
        Map<String, Integer> visibleVideosByPerson = new HashMap<>();
        int posterCount = 0;
        for (MediaVideo video : videos)
        {
            if (StringUtils.isNotEmpty(video.getPosterPath()))
            {
                posterCount++;
            }
            Map<String, Object> node = toVideoNode(video, tagsByVideo.get(video.getId()),
                    actorsByVideo.get(video.getId()), personsByVideo.get(video.getId()),
                    studiosByVideo.get(video.getId()));
            putDerived(node, video, spriteKeys, previewKeys);
            videoNodes.add(node);
            for (Map<String, Object> bound : personsByVideo.getOrDefault(video.getId(), List.of()))
            {
                putLater(videoMtimeByPerson, stringOf(bound.get("name")), video.getMtime());
                String personName = stringOf(bound.get("name"));
                if (personName != null)
                {
                    visibleVideosByPerson.merge(personName, Integer.valueOf(1), Integer::sum);
                }
            }
        }

        List<MediaAlbum> albums = mediaAlbumMapper.selectAlbumList();
        Map<String, List<Map<String, Object>>> photosByAlbum = new HashMap<>();
        // 未登录时「含隐藏根图片」的相册整册不输出（相册自己没有 root_id，只能由图片反推）
        // P5.99：分级收紧的相册同样整册不输出（整册一个分级，册内图片继承它）
        Set<String> hiddenAlbums = new HashSet<>();
        if (!hiddenRatings.isEmpty())
        {
            for (MediaAlbum album : albums)
            {
                if (isHiddenRating(album.getContentRating(), hiddenRatings))
                {
                    hiddenAlbums.add(album.getId());
                }
            }
        }
        int photoCount = 0;
        for (MediaPhoto photo : mediaPhotoMapper.selectPhotoList(keepMissing))
        {
            if (hiddenAlbums.contains(photo.getAlbumId()))
            {
                // 整册已因分级收紧被筛掉：它的图片也不该进 stats.photos
                continue;
            }
            if (isHiddenRoot(photo.getRootId(), hidden))
            {
                hiddenAlbums.add(photo.getAlbumId());
                continue;
            }
            photosByAlbum.computeIfAbsent(photo.getAlbumId(), k -> new ArrayList<>()).add(toPhotoNode(photo));
            photoCount++;
        }

        Map<Long, String> personNames = loadPersonNames();
        List<Map<String, Object>> albumNodes = new ArrayList<>(albums.size());
        Map<Long, List<MediaAlbum>> albumsByPerson = new LinkedHashMap<>();
        for (MediaAlbum album : albums)
        {
            if (hiddenAlbums.contains(album.getId()))
            {
                continue;
            }
            albumNodes.add(toAlbumNode(album, personNames,
                    photosByAlbum.getOrDefault(album.getId(), List.of()),
                    personNamesByAlbum.getOrDefault(album.getId(), List.of())));
            if (album.getPersonId() != null)
            {
                albumsByPerson.computeIfAbsent(album.getPersonId(), k -> new ArrayList<>()).add(album);
                putLater(albumMtimeByPerson, album.getPersonId(), album.getMtime());
            }
        }

        List<Map<String, Object>> personNodes = new ArrayList<>();
        boolean anyRestriction = !hidden.isEmpty() || !hiddenRatings.isEmpty();
        int personCount = 0;
        for (MediaPerson person : mediaPersonMapper.selectPersonList())
        {
            List<MediaAlbum> own = albumsByPerson.getOrDefault(person.getId(), List.of());
            List<String> albumIds = new ArrayList<>(own.size());
            long photos = 0;
            for (MediaAlbum album : own)
            {
                albumIds.add(album.getId());
                photos += album.getPhotoCount() == null ? 0 : album.getPhotoCount();
            }
            int ownVideos = visibleVideosByPerson.getOrDefault(person.getName(), Integer.valueOf(0))
                    .intValue();
            if (anyRestriction && albumIds.isEmpty() && ownVideos == 0)
            {
                // 未登录且该人物名下没有任何可见作品（相册与视频都 0）：整条不输出
                //（否则「人名的存在」本身也会泄出去）
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", person.getName());
            node.put("count", own.size());
            // P5.99：可见视频条数（未登录口径）。前端人物墙仍按 videos[].persons 自行累加，
            // 这个键只服务「名下有视频但相册全不可见」的人物在站端仍能显示计数。
            node.put("videos", ownVideos);
            node.put("photos", photos);
            node.put("albums", albumIds);
            node.put("cover", own.isEmpty() ? null : own.get(0).getCoverPath());
            // P5.45：最近更新 = 名下相册 / 视频的 mtime 最大值（epoch 毫秒，站端按本地时区格式化成 YYYY-MM-DD）。
            // 两者都没有（空壳人物）时整键不输出 —— 前端据此不渲染「最近」行，不留空洞。
            Date latest = later(albumMtimeByPerson.get(person.getId()), videoMtimeByPerson.get(person.getName()));
            if (latest != null)
            {
                node.put("mtime", latest.getTime());
            }
            personNodes.add(node);
            personCount++;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("videos", videos.size());
        stats.put("albums", albumNodes.size());
        stats.put("photos", photoCount);
        stats.put("posters", posterCount);
        stats.put("people", personCount);

        // P5.99：分级筛选条的数据源 —— 只输出「当前访客可见 + 确实有条目」的分级。
        // 只有 1 项时前端不渲染筛选（站长把某档关成未登录不可见后，访客这边就不该看到那一档，
        // 更不该看到一个必然为空的筛选按钮）。
        List<Map<String, Object>> ratingNodes = buildRatingNodes(videoNodes, albumNodes, hiddenRatings);

        List<String> facetOrder = new ArrayList<>(FACET_ORDER);
        for (String facet : facets)
        {
            if (!facetOrder.contains(facet))
            {
                facetOrder.add(facet);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("built", formatBuilt(maxMtime()));
        out.put("root", displayRoot());
        out.put("stats", stats);
        out.put("ratings", ratingNodes);
        out.put("facetOrder", facetOrder);
        out.put("videos", videoNodes);
        out.put("albums", albumNodes);
        out.put("people", personNodes);
        return out;
    }

    /**
     * 分级筛选条节点（P5.99）：{@code [{value,label,count}]}
     *
     * <p>口径两条：</p>
     * <ol>
     *   <li><b>只含当前访客可见的分级</b>（{@code media_rating_policy.guest_visible=0} 的那一档不出现）——
     *       否则筛选器会暴露「这一档存在但你看不到」；</li>
     *   <li><b>只含有条目的分级</b>（该档在当前可见集合里一条视频 / 相册都没有就不出现）——
     *       一个必然为空的筛选项只是死 UI。</li>
     * </ol>
     * <p>{@code count} = 该档可见视频数 + 可见相册数（两种条目在站上是并列的两块内容，
     * 计数只服务「这一档有多少东西」的直觉，不参与任何判定）。</p>
     */
    private static List<Map<String, Object>> buildRatingNodes(List<Map<String, Object>> videoNodes,
            List<Map<String, Object>> albumNodes, Set<String> hiddenRatings)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : MediaRating.values())
        {
            counts.put(value, Integer.valueOf(0));
        }
        for (Map<String, Object> node : videoNodes)
        {
            bumpRating(counts, node.get("contentRating"));
        }
        for (Map<String, Object> node : albumNodes)
        {
            bumpRating(counts, node.get("contentRating"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet())
        {
            if (isHiddenRating(entry.getKey(), hiddenRatings))
            {
                continue;
            }
            if (entry.getValue() == null || entry.getValue().intValue() <= 0)
            {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("value", entry.getKey());
            node.put("label", MediaRating.labelOf(entry.getKey()));
            node.put("count", entry.getValue());
            out.add(node);
        }
        return out;
    }

    private static void bumpRating(Map<String, Integer> counts, Object rating)
    {
        String value = MediaRating.normalize(rating == null ? null : String.valueOf(rating));
        Integer prev = counts.get(value);
        if (prev != null)
        {
            counts.put(value, Integer.valueOf(prev.intValue() + 1));
        }
    }

    /**
     * 单个工作室节点：{@code {id,name}}
     *
     * <p>管理端那份（{@code selectVideoStudios}）还带 alias 等编辑字段，站端一律不需要：
     * 名字用于展示 / 筛选，id 用于详情与元信息对齐。</p>
     *
     * @return 名称为空时返回 null（调用方据此跳过该行）
     */
    private static Map<String, Object> studioNode(Map<String, Object> row)
    {
        String name = stringOf(row.get("name"));
        if (name == null)
        {
            return null;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", row.get("id"));
        node.put("name", name);
        return node;
    }

    /** 工作室节点列表；空 / 全无效时返回 null（沿用 videos[].studios「非空才输出」口径） */
    private static List<Map<String, Object>> studioNodes(List<Map<String, Object>> rows)
    {
        if (rows == null || rows.isEmpty())
        {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> node = studioNode(row);
            if (node != null)
            {
                out.add(node);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 单个人物绑定节点：{@code {id,name,role?}}（role 为 null 时整键不输出） */
    private static Map<String, Object> personNode(Map<String, Object> row)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", row.get("personId"));
        node.put("name", row.get("name"));
        String role = stringOf(row.get("role"));
        if (role != null)
        {
            node.put("role", role);
        }
        return node;
    }

    /** 单片详情用：该视频绑定的人物（含角色），未绑定时返回 null（= 整键不输出） */
    private List<Map<String, Object>> videoPersons(String videoId)
    {
        List<Map<String, Object>> rows = mediaPersonMapper.selectPersonActorsByVideoId(videoId);
        if (rows == null || rows.isEmpty())
        {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows)
        {
            out.add(personNode(row));
        }
        return out;
    }

    /** 单册详情用：该相册的人物名（未绑定时返回 null） */
    private List<String> albumPersonNames(String albumId)
    {
        List<Map<String, Object>> rows = mediaPersonMapper.selectAlbumPersons(albumId);
        if (rows == null || rows.isEmpty())
        {
            return null;
        }
        List<String> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows)
        {
            String name = stringOf(row.get("name"));
            if (name != null && !out.contains(name))
            {
                out.add(name);
            }
        }
        return out;
    }

    private static String stringOf(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Map<Long, String> loadPersonNames()
    {
        Map<Long, String> names = new HashMap<>();
        for (MediaPerson person : mediaPersonMapper.selectPersonList())
        {
            names.put(person.getId(), person.getName());
        }
        return names;
    }

    private Map<String, Object> toVideoNode(MediaVideo video, List<String[]> tags, List<String> actors,
            List<Map<String, Object>> persons, List<Map<String, Object>> studios)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", video.getId());
        node.put("title", video.getTitle());
        node.put("folder", video.getFolder());
        node.put("rel", video.getRelPath());
        node.put("size", video.getSize());
        node.put("dur", video.getDur());
        node.put("w", video.getW());
        node.put("h", video.getH());
        node.put("vcodec", video.getVcodec());
        // P5.16 帧率：前端用它把拖动落点吸到帧栅格（t = round(t*fps)/fps）并显示 hh:mm:ss:ff。
        // 未探测（NULL）时整键不输出 —— 与 rating / persons 同一约定：老库索引体积不涨一个字节，
        // 前端拿不到 fps 就退回「按秒拖动」，行为与 P5.15 完全一致。
        if (video.getFps() != null)
        {
            node.put("fps", video.getFps());
        }
        node.put("browserOk", isTrue(video.getBrowserOk()));
        node.put("tags", tags == null ? List.of() : tags);
        node.put("actors", actors == null ? List.of() : actors);
        // 组织属性（M3-A）：字典标签只给名称、评分只给数值，**为空时整键不输出**，
        // 没整理过的库（绝大多数视频）索引体积一个字节都不涨。
        List<String> dictTags = new ArrayList<>();
        if (tags != null)
        {
            for (String[] pair : tags)
            {
                if (pair != null && pair.length > 1 && FACET_TAG.equals(pair[0]))
                {
                    dictTags.add(pair[1]);
                }
            }
        }
        if (!dictTags.isEmpty())
        {
            node.put("dictTags", dictTags);
        }
        if (video.getRating() != null)
        {
            node.put("rating", video.getRating());
        }
        // P5.99 内容分级：恒输出（列 NOT NULL 默认 general）。注意键名是 contentRating ——
        // 上面的 rating 是人工评分 0-100，两者不是一回事，站端筛选按这一档走。
        node.put("contentRating", MediaRating.normalize(video.getContentRating()));
        // 人物（P3-A）：非空才输出，没绑人物的视频索引体积一个字节都不涨
        if (persons != null && !persons.isEmpty())
        {
            node.put("persons", persons);
        }
        // 工作室：同样非空才输出；值是 {@code [{id,name}]}（站端按名字筛选 / 展示，id 留给详情与元信息对齐）
        if (studios != null && !studios.isEmpty())
        {
            node.put("studios", studios);
        }
        // 作品简介（P5.38）：非空才输出，与 rating / persons / studios 同一约定。
        // 列表查询不 select synopsis（见 MediaVideoMapper.xml 的 videoColumns），
        // 所以 /api/media/index 的条目永远不会带这个键 —— 体积不变；
        // 只有单片详情（GET /api/media/video/{id}）会带上，信息栏首屏一次请求即可渲染。
        if (video.getSynopsis() != null && !video.getSynopsis().isEmpty())
        {
            node.put("synopsis", video.getSynopsis());
        }
        node.put("poster", video.getPosterPath());
        node.put("doc", video.getDocPath());
        node.put("sub", video.getSubPath());
        return node;
    }

    /**
     * 视频条目补派生资源标记（sprite / 预览视频）
     *
     * <p>命中规则与服务端解析完全一致：文件名是 {@code content_hash} 或老命名 {@code id} 都算有。
     * 前端据此决定要不要请求 /media/sprite|preview/{id}，不靠逐个探测 404。</p>
     */
    private static void putDerived(Map<String, Object> node, MediaVideo video, Set<String> spriteKeys,
            Set<String> previewKeys)
    {
        String hash = video.getContentHash() == null ? "" : video.getContentHash().trim();
        String id = video.getId();
        node.put("hasSprite", hasDerivedKey(spriteKeys, hash, id));
        node.put("hasPreview", hasDerivedKey(previewKeys, hash, id));
    }

    private static boolean hasDerivedKey(Set<String> keys, String hash, String id)
    {
        if (!hash.isEmpty() && keys.contains(hash))
        {
            return true;
        }
        return id != null && keys.contains(id);
    }

    private Map<String, Object> toAlbumNode(MediaAlbum album, Map<Long, String> personNames,
            List<Map<String, Object>> photos, List<String> persons)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", album.getId());
        node.put("title", album.getTitle());
        node.put("folder", parentOf(album.getDir()));
        node.put("dir", album.getDir());
        node.put("person", album.getPersonId() == null ? null : personNames.get(album.getPersonId()));
        node.put("count", album.getPhotoCount() == null ? photos.size() : album.getPhotoCount());
        node.put("bytes", album.getBytes());
        node.put("cover", album.getCoverPath());
        node.put("note", album.getNote());
        // P5.99 内容分级：恒输出（与视频条目同名同义）
        node.put("contentRating", MediaRating.normalize(album.getContentRating()));
        // 人物（P3-A）：只给名字数组，非空才输出（前端人物页按名字匹配 albums[].persons）
        if (persons != null && !persons.isEmpty())
        {
            node.put("persons", persons);
        }
        node.put("photos", photos);
        return node;
    }

    /**
     * 相册内一张图：{@code rel / i / big / hash}
     *
     * <p>{@code hash} = media_photo.content_hash（P4 起缩略图按内容寻址 p/&lt;hash&gt;.jpg）。
     * 前端可以拿它直接拼 {@code /api/media/thumb/p/<albumId>/<hash>}，也可以继续用
     * {@code /thumb/p/<albumId>/<i>}——服务端两种都会先找 {@code p/<hash>.jpg} 再回退老命名。
     * 哈希未回填时<b>整键不输出</b>（老库索引体积一个字节都不涨）。</p>
     */
    private Map<String, Object> toPhotoNode(MediaPhoto photo)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rel", photo.getRelPath());
        node.put("i", photo.getSeq());
        node.put("big", isTrue(photo.getIsBig()));
        if (StringUtils.isNotEmpty(photo.getContentHash()))
        {
            node.put("hash", photo.getContentHash());
        }
        return node;
    }

    // ------------------------------------------------------------------ 工具

    /** 两个时间取较晚的那个（任一侧为 null 就取另一侧；都 null → null） */
    private static Date later(Date a, Date b)
    {
        if (a == null)
        {
            return b;
        }
        if (b == null)
        {
            return a;
        }
        return b.after(a) ? b : a;
    }

    /** 往「键 → 最大 mtime」表里记一条（键 / 值为 null 直接忽略，避免 merge 对 null 值抛 NPE） */
    private static <K> void putLater(Map<K, Date> target, K key, Date value)
    {
        if (key == null || value == null)
        {
            return;
        }
        Date prev = target.get(key);
        if (prev == null || value.after(prev))
        {
            target.put(key, value);
        }
    }

    private Date maxMtime()
    {
        Date videoMax = mediaVideoMapper.selectMaxMtime();
        Date albumMax = mediaAlbumMapper.selectMaxMtime();
        if (videoMax == null)
        {
            return albumMax;
        }
        if (albumMax == null)
        {
            return videoMax;
        }
        return videoMax.after(albumMax) ? videoMax : albumMax;
    }

    /** built 取数据自身最大修改时间，保证同一份数据重建时 ETag 不变 */
    private String formatBuilt(Date mtime)
    {
        if (mtime == null)
        {
            return "";
        }
        return new SimpleDateFormat(BUILT_PATTERN).format(mtime);
    }

    private String displayRoot()
    {
        String root = mediaProperties.getRootPath();
        if (StringUtils.isEmpty(root))
        {
            return "";
        }
        return Paths.get(root).toAbsolutePath().normalize().toString();
    }

    /** dir 的首段即分类，如 写真/xxx -> 写真 */
    private static String parentOf(String dir)
    {
        if (StringUtils.isEmpty(dir))
        {
            return null;
        }
        int cut = dir.indexOf('/');
        int cutWin = dir.indexOf('\\');
        if (cutWin >= 0 && (cut < 0 || cutWin < cut))
        {
            cut = cutWin;
        }
        return cut < 0 ? dir : dir.substring(0, cut);
    }

    private static boolean isTrue(Integer flag)
    {
        return flag != null && flag != 0;
    }

    /** body 的 MD5 前 12 位十六进制，作为强 ETag 的稳定版本号 */
    private static String digest(byte[] body)
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("MD5").digest(body);
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++)
            {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // MD5 是 JDK 必备算法，走到这里说明运行环境异常
            throw new IllegalStateException(e);
        }
    }
}
