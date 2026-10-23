package com.qtools.media.service.impl;

import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.domain.MediaVideoActor;
import com.qtools.media.domain.MediaVideoTag;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaAlbumMapper;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.mapper.MediaPhotoMapper;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaIndexService;

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

    @Autowired
    private MediaProperties mediaProperties;

    /** 派生资源（sprite / pv2）目录清单：索引批量判断 hasSprite / hasPreview 用 */
    @Autowired
    private IMediaFileService mediaFileService;

    private volatile MediaIndexSnapshot snapshot;

    @Override
    public MediaIndexSnapshot indexSnapshot()
    {
        MediaIndexSnapshot cached = snapshot;
        if (cached != null && System.currentTimeMillis() - cached.getBuiltAt() < CACHE_TTL_MS)
        {
            return cached;
        }
        synchronized (this)
        {
            cached = snapshot;
            if (cached != null && System.currentTimeMillis() - cached.getBuiltAt() < CACHE_TTL_MS)
            {
                return cached;
            }
            long start = System.currentTimeMillis();
            Map<String, Object> data = buildIndex();
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
            snapshot = fresh;
            log.info("媒体索引重建完成 size={}B cost={}ms etag={}", body.length, System.currentTimeMillis() - start,
                    fresh.getEtag());
            return fresh;
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
        return toVideoNode(video, tags, actors);
    }

    @Override
    public Map<String, Object> selectAlbumDetail(String id)
    {
        MediaAlbum album = mediaAlbumMapper.selectAlbumById(id);
        if (album == null)
        {
            throw MediaException.notFound();
        }
        Map<Long, String> personNames = loadPersonNames();
        List<Map<String, Object>> photos = new ArrayList<>();
        for (MediaPhoto photo : mediaPhotoMapper.selectPhotoListByAlbumId(id))
        {
            photos.add(toPhotoNode(photo));
        }
        return toAlbumNode(album, personNames, photos);
    }

    @Override
    public void invalidateSnapshot()
    {
        snapshot = null;
    }

    // ------------------------------------------------------------------ 构建

    private Map<String, Object> buildIndex()
    {
        List<MediaVideo> videos = mediaVideoMapper.selectVideoList();
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

        // 派生资源存在性：一次目录扫描成集合，逐条判断不再 stat（万级视频下差别是一个量级）
        Set<String> spriteKeys = mediaFileService.spriteKeys();
        Set<String> previewKeys = mediaFileService.previewKeys();

        List<Map<String, Object>> videoNodes = new ArrayList<>(videos.size());
        int posterCount = 0;
        for (MediaVideo video : videos)
        {
            if (StringUtils.isNotEmpty(video.getPosterPath()))
            {
                posterCount++;
            }
            Map<String, Object> node = toVideoNode(video, tagsByVideo.get(video.getId()),
                    actorsByVideo.get(video.getId()));
            putDerived(node, video, spriteKeys, previewKeys);
            videoNodes.add(node);
        }

        List<MediaAlbum> albums = mediaAlbumMapper.selectAlbumList();
        Map<String, List<Map<String, Object>>> photosByAlbum = new HashMap<>();
        int photoCount = 0;
        for (MediaPhoto photo : mediaPhotoMapper.selectPhotoList())
        {
            photosByAlbum.computeIfAbsent(photo.getAlbumId(), k -> new ArrayList<>()).add(toPhotoNode(photo));
            photoCount++;
        }

        Map<Long, String> personNames = loadPersonNames();
        List<Map<String, Object>> albumNodes = new ArrayList<>(albums.size());
        Map<Long, List<MediaAlbum>> albumsByPerson = new LinkedHashMap<>();
        for (MediaAlbum album : albums)
        {
            albumNodes.add(toAlbumNode(album, personNames,
                    photosByAlbum.getOrDefault(album.getId(), List.of())));
            if (album.getPersonId() != null)
            {
                albumsByPerson.computeIfAbsent(album.getPersonId(), k -> new ArrayList<>()).add(album);
            }
        }

        List<Map<String, Object>> personNodes = new ArrayList<>();
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
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", person.getName());
            node.put("count", own.size());
            node.put("photos", photos);
            node.put("albums", albumIds);
            node.put("cover", own.isEmpty() ? null : own.get(0).getCoverPath());
            personNodes.add(node);
            personCount++;
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("videos", videos.size());
        stats.put("albums", albums.size());
        stats.put("photos", photoCount);
        stats.put("posters", posterCount);
        stats.put("people", personCount);

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
        out.put("facetOrder", facetOrder);
        out.put("videos", videoNodes);
        out.put("albums", albumNodes);
        out.put("people", personNodes);
        return out;
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

    private Map<String, Object> toVideoNode(MediaVideo video, List<String[]> tags, List<String> actors)
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
            List<Map<String, Object>> photos)
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
        node.put("photos", photos);
        return node;
    }

    private Map<String, Object> toPhotoNode(MediaPhoto photo)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rel", photo.getRelPath());
        node.put("i", photo.getSeq());
        node.put("big", isTrue(photo.getIsBig()));
        return node;
    }

    // ------------------------------------------------------------------ 工具

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
