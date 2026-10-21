package com.qtools.media.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaOwnerSession;
import com.qtools.media.domain.MediaUserItem;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaFavoriteMapper;
import com.qtools.media.mapper.MediaPhotoMapper;
import com.qtools.media.mapper.MediaRecentMapper;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaOwnerService;
import com.qtools.media.service.IMediaUserService;

/**
 * 用户数据服务实现
 *
 * <p>落库策略（容易踩坑，别改成 ON DUPLICATE KEY）：
 * media_favorite / media_recent 的唯一键含 NULL 列（video 行的 album_id/seq、photo 行的
 * target_id 都是 NULL），MySQL 唯一索引不判 NULL 重复，upsert 会写出重复行。因此统一用
 * 「按 kind 分支的精确匹配」+ {@code INSERT ... WHERE NOT EXISTS}，命中时只刷新时间戳。</p>
 *
 * <p>同一 JVM 内用分段锁把同一 owner 的写入串行化，避免双击/并发请求在同一毫秒内
 * 各插一行（多实例部署时需要改成 DB 级锁，当前单实例 8080 不涉及）。</p>
 *
 * @author qtools
 */
@Service
public class MediaUserServiceImpl implements IMediaUserService
{
    private static final Logger log = LoggerFactory.getLogger(MediaUserServiceImpl.class);

    private static final String KIND_VIDEO = "video";

    private static final String KIND_PHOTO = "photo";

    /** 最近浏览每类保留条数 */
    private static final int RECENT_MAX = 50;

    /** 收藏返回条数上限（存的不设上限） */
    private static final int FAV_RETURN_MAX = 200;

    /** 收藏存量上限，超出淘汰最旧的（对齐 Node FAV_MAX） */
    private static final int FAV_KEEP_MAX = 2000;

    /** 标题截断长度（对齐 Node clipText 的 200） */
    private static final int TITLE_MAX = 200;

    private static final int LOCK_STRIPES = 64;

    @Autowired
    private MediaFavoriteMapper mediaFavoriteMapper;

    @Autowired
    private MediaRecentMapper mediaRecentMapper;

    @Autowired
    private MediaVideoMapper mediaVideoMapper;

    @Autowired
    private MediaPhotoMapper mediaPhotoMapper;

    @Autowired
    private IMediaOwnerService mediaOwnerService;

    /** 按 owner_key 分段的写锁 */
    private final Object[] writeLocks = new Object[LOCK_STRIPES];

    public MediaUserServiceImpl()
    {
        for (int i = 0; i < writeLocks.length; i++)
        {
            writeLocks[i] = new Object();
        }
    }

    // ------------------------------------------------------------------ 读取

    @Override
    public Map<String, Object> selectUser(MediaOwnerSession session)
    {
        return userNode(session);
    }

    @Override
    public List<Map<String, Object>> selectFavoriteList(MediaOwnerSession session)
    {
        return favoriteNodes(session.getOwnerKey());
    }

    @Override
    public Map<String, Object> selectRecentList(MediaOwnerSession session, String kind)
    {
        String key = session.getOwnerKey();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videos", kind == null || KIND_VIDEO.equals(kind) ? recentNodes(key, KIND_VIDEO) : List.of());
        out.put("photos", kind == null || KIND_PHOTO.equals(kind) ? recentNodes(key, KIND_PHOTO) : List.of());
        return out;
    }

    @Override
    public Map<String, Object> selectMe(MediaOwnerSession session)
    {
        String key = session.getOwnerKey();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("favVideo", mediaFavoriteMapper.countByOwner(key, KIND_VIDEO));
        counts.put("favPhoto", mediaFavoriteMapper.countByOwner(key, KIND_PHOTO));
        counts.put("recentVideo", mediaRecentMapper.countByOwner(key, KIND_VIDEO));
        counts.put("recentPhoto", mediaRecentMapper.countByOwner(key, KIND_PHOTO));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerKey", key);
        out.put("profile", profileNode(session));
        out.put("counts", counts);
        return out;
    }

    // ------------------------------------------------------------------ 写入

    @Override
    public Map<String, Object> addFavorite(MediaOwnerSession session, String kind, String id, String album,
            Integer seq)
    {
        String validKind = requireKind(kind);
        if (KIND_VIDEO.equals(validKind))
        {
            String videoId = requireText(id, "video 需要非空 id");
            if (mediaVideoMapper.selectVideoById(videoId) == null)
            {
                throw MediaException.notFound();
            }
            writeFavorite(session.getOwnerKey(), validKind, videoId, null, null);
        }
        else
        {
            String albumId = requireText(album, "photo 需要非空 album");
            Integer index = requireSeq(seq);
            if (mediaPhotoMapper.countPhotoByAlbumAndSeq(albumId, index) == 0)
            {
                throw MediaException.notFound();
            }
            writeFavorite(session.getOwnerKey(), validKind, null, albumId, index);
        }
        return userNode(session);
    }

    @Override
    public Map<String, Object> removeFavorite(MediaOwnerSession session, String kind, String id, String album,
            Integer seq)
    {
        String validKind = requireKind(kind);
        String key = session.getOwnerKey();
        if (KIND_VIDEO.equals(validKind))
        {
            String videoId = requireText(id, "video 需要非空 id");
            mediaFavoriteMapper.deleteExact(key, validKind, videoId, null, null);
        }
        else
        {
            String albumId = requireText(album, "photo 需要非空 album");
            Integer index = requireSeq(seq);
            mediaFavoriteMapper.deleteExact(key, validKind, null, albumId, index);
        }
        return userNode(session);
    }

    @Override
    public Map<String, Object> addRecent(MediaOwnerSession session, String kind, String id, String album,
            Integer seq, Double pos, Double dur)
    {
        String validKind = requireKind(kind);
        String key = session.getOwnerKey();
        if (KIND_VIDEO.equals(validKind))
        {
            String videoId = requireText(id, "video 需要非空 id");
            if (mediaVideoMapper.selectVideoById(videoId) == null)
            {
                throw MediaException.notFound();
            }
            writeRecent(key, validKind, videoId, null, null, pos, dur);
        }
        else
        {
            String albumId = requireText(album, "photo 需要非空 album");
            Integer index = requireSeq(seq);
            if (mediaPhotoMapper.countPhotoByAlbumAndSeq(albumId, index) == 0)
            {
                throw MediaException.notFound();
            }
            writeRecent(key, validKind, null, albumId, index, null, null);
        }
        return selectRecentList(session, null);
    }

    @Override
    public Map<String, Object> mergeUser(MediaOwnerSession session, Map<String, Object> body)
    {
        if (body == null)
        {
            throw MediaException.badRequest("body 必须是 JSON 对象");
        }
        String key = session.getOwnerKey();
        boolean touched = false;
        int skipped = 0;

        if (body.containsKey("profile"))
        {
            mediaOwnerService.applyProfile(session, asMap(body.get("profile"), "profile"));
            touched = true;
        }
        if (body.containsKey("favorites"))
        {
            for (Object item : asList(body.get("favorites"), "favorites"))
            {
                if (!upsertFavorite(key, asMap(item, "favorites[]")))
                {
                    skipped++;
                }
            }
            synchronized (lock(key))
            {
                mediaFavoriteMapper.prune(key, FAV_KEEP_MAX);
            }
            touched = true;
        }
        if (body.containsKey("recentVideos"))
        {
            for (Object item : asList(body.get("recentVideos"), "recentVideos"))
            {
                if (!upsertRecent(key, asMap(item, "recentVideos[]"), KIND_VIDEO))
                {
                    skipped++;
                }
            }
            trimRecent(key, KIND_VIDEO);
            touched = true;
        }
        if (body.containsKey("recentPhotos"))
        {
            for (Object item : asList(body.get("recentPhotos"), "recentPhotos"))
            {
                if (!upsertRecent(key, asMap(item, "recentPhotos[]"), KIND_PHOTO))
                {
                    skipped++;
                }
            }
            trimRecent(key, KIND_PHOTO);
            touched = true;
        }
        // Node 版单条形态：{fav:{...add}} / {recent:{...}}，为了前端平替一并接受
        if (body.containsKey("fav"))
        {
            Map<String, Object> fav = asMap(body.get("fav"), "fav");
            boolean add = !Boolean.FALSE.equals(fav.get("add"));
            if (add)
            {
                if (!upsertFavorite(key, fav))
                {
                    skipped++;
                }
            }
            else
            {
                deleteFavoriteOf(key, fav);
            }
            touched = true;
        }
        if (body.containsKey("recent"))
        {
            Map<String, Object> recent = asMap(body.get("recent"), "recent");
            if (!upsertRecent(key, recent, null))
            {
                skipped++;
            }
            touched = true;
        }
        if (!touched)
        {
            throw MediaException.badRequest("body 需含 profile / favorites / recentVideos / recentPhotos 之一");
        }
        if (skipped > 0)
        {
            log.debug("合并更新跳过了 {} 条指向已不存在媒体的条目 owner={}", skipped, key);
        }
        return userNode(session);
    }

    // ------------------------------------------------------------------ 写入细节

    private void writeFavorite(String ownerKey, String kind, String targetId, String albumId, Integer seq)
    {
        synchronized (lock(ownerKey))
        {
            int inserted = mediaFavoriteMapper.insertIfAbsent(ownerKey, kind, targetId, albumId, seq);
            if (inserted == 0)
            {
                mediaFavoriteMapper.touch(ownerKey, kind, targetId, albumId, seq);
            }
            mediaFavoriteMapper.prune(ownerKey, FAV_KEEP_MAX);
        }
    }

    private void writeRecent(String ownerKey, String kind, String targetId, String albumId, Integer seq, Double pos,
            Double dur)
    {
        synchronized (lock(ownerKey))
        {
            Double normalizedPos = pos == null ? null : positiveOrZero(pos);
            Double normalizedDur = dur == null ? null : positiveOrZero(dur);
            int inserted = mediaRecentMapper.insertIfAbsent(ownerKey, kind, targetId, albumId, seq, normalizedPos,
                    normalizedDur);
            if (inserted == 0)
            {
                mediaRecentMapper.touch(ownerKey, kind, targetId, albumId, seq, normalizedPos, normalizedDur);
            }
            mediaRecentMapper.trim(ownerKey, kind, RECENT_MAX);
        }
    }

    private void trimRecent(String ownerKey, String kind)
    {
        synchronized (lock(ownerKey))
        {
            mediaRecentMapper.trim(ownerKey, kind, RECENT_MAX);
        }
    }

    /**
     * 批量形态的收藏 upsert：形状非法一律 400，指向已不存在的媒体则跳过（批量里夹着
     * 一条过期数据不应让整个请求失败），跳过条数记 debug 日志。
     */
    private boolean upsertFavorite(String ownerKey, Map<String, Object> item)
    {
        String kind = requireKind(str(item.get("kind")));
        if (KIND_VIDEO.equals(kind))
        {
            String id = requireText(str(item.get("id")), "video 需要非空 id");
            if (mediaVideoMapper.selectVideoById(id) == null)
            {
                return false;
            }
            writeFavorite(ownerKey, kind, id, null, null);
            return true;
        }
        String album = requireText(str(item.get("album")), "photo 需要非空 album");
        Integer seq = requireSeq(intOf(item.containsKey("seq") ? item.get("seq") : item.get("i")));
        if (mediaPhotoMapper.countPhotoByAlbumAndSeq(album, seq) == 0)
        {
            return false;
        }
        writeFavorite(ownerKey, kind, null, album, seq);
        return true;
    }

    /** 批量形态的最近浏览 upsert；defaultKind 为 null 时按条目自带 kind */
    private boolean upsertRecent(String ownerKey, Map<String, Object> item, String defaultKind)
    {
        String kind = requireKind(str(item.get("kind")) == null ? defaultKind : str(item.get("kind")));
        if (KIND_VIDEO.equals(kind))
        {
            String id = requireText(str(item.get("id")), "video 需要非空 id");
            if (mediaVideoMapper.selectVideoById(id) == null)
            {
                return false;
            }
            writeRecent(ownerKey, kind, id, null, null, doubleOf(item.get("pos")), doubleOf(item.get("dur")));
            return true;
        }
        String album = requireText(str(item.get("album")), "photo 需要非空 album");
        Integer seq = requireSeq(intOf(item.containsKey("seq") ? item.get("seq") : item.get("i")));
        if (mediaPhotoMapper.countPhotoByAlbumAndSeq(album, seq) == 0)
        {
            return false;
        }
        writeRecent(ownerKey, kind, null, album, seq, null, null);
        return true;
    }

    private void deleteFavoriteOf(String ownerKey, Map<String, Object> item)
    {
        String kind = requireKind(str(item.get("kind")));
        if (KIND_VIDEO.equals(kind))
        {
            mediaFavoriteMapper.deleteExact(ownerKey, kind, requireText(str(item.get("id")), "video 需要非空 id"),
                    null, null);
            return;
        }
        String album = requireText(str(item.get("album")), "photo 需要非空 album");
        Integer seq = requireSeq(intOf(item.containsKey("seq") ? item.get("seq") : item.get("i")));
        mediaFavoriteMapper.deleteExact(ownerKey, kind, null, album, seq);
    }

    // ------------------------------------------------------------------ 组装响应

    private Map<String, Object> userNode(MediaOwnerSession session)
    {
        String key = session.getOwnerKey();
        List<Map<String, Object>> favorites = favoriteNodes(key);
        List<Map<String, Object>> recentVideos = recentNodes(key, KIND_VIDEO);
        List<Map<String, Object>> recentPhotos = recentNodes(key, KIND_PHOTO);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerKey", key);
        out.put("profile", profileNode(session));
        out.put("favorites", favorites);
        out.put("recentVideos", recentVideos);
        out.put("recentPhotos", recentPhotos);
        // Node data/user.json 的嵌套形状，前端可原样平替
        Map<String, Object> recent = new LinkedHashMap<>();
        recent.put("videos", recentVideos);
        recent.put("photos", recentPhotos);
        out.put("recent", recent);
        return out;
    }

    private Map<String, Object> profileNode(MediaOwnerSession session)
    {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("nick", session.getNick());
        profile.put("avatar", session.getAvatar());
        return profile;
    }

    private List<Map<String, Object>> favoriteNodes(String ownerKey)
    {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (MediaUserItem item : mediaFavoriteMapper.selectItemList(ownerKey, FAV_RETURN_MAX))
        {
            nodes.add(itemNode(item, false));
        }
        return nodes;
    }

    private List<Map<String, Object>> recentNodes(String ownerKey, String kind)
    {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (MediaUserItem item : mediaRecentMapper.selectItemList(ownerKey, kind, RECENT_MAX))
        {
            nodes.add(itemNode(item, KIND_VIDEO.equals(kind)));
        }
        return nodes;
    }

    private Map<String, Object> itemNode(MediaUserItem item, boolean withPos)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", item.getKind());
        if (KIND_PHOTO.equals(item.getKind()))
        {
            int seq = item.getSeq() == null ? 0 : item.getSeq();
            node.put("album", item.getAlbumId());
            node.put("i", seq);
            node.put("seq", seq);
            node.put("title", photoTitle(item.getTitle(), item.getAlbumId(), seq));
            node.put("thumb", photoThumb(item.getAlbumId(), seq));
        }
        else
        {
            String id = item.getTargetId();
            node.put("id", id);
            node.put("title", clip(StringUtils.isEmpty(item.getTitle()) ? id : item.getTitle(), TITLE_MAX));
            node.put("thumb", "thumbs/v/" + id + ".jpg");
        }
        node.put("t", item.getHappenedAt() == null ? 0L : item.getHappenedAt().getTime());
        if (withPos)
        {
            node.put("pos", item.getPos() == null ? 0D : item.getPos());
            node.put("dur", item.getDur() == null ? 0D : item.getDur());
        }
        return node;
    }

    /** 图片标题沿用 Node 版：相册标题 + " #" + (seq+1) */
    private static String photoTitle(String albumTitle, String albumId, int seq)
    {
        String base = StringUtils.isEmpty(albumTitle) ? String.valueOf(albumId) : albumTitle;
        return clip(base + " #" + (seq + 1), TITLE_MAX);
    }

    /** 缩略图路径沿用 CONTRACT §3.2：thumbs/p/<album>/<seq 补 4 位>.jpg */
    private static String photoThumb(String albumId, int seq)
    {
        return String.format("thumbs/p/%s/%04d.jpg", albumId, seq);
    }

    // ------------------------------------------------------------------ 校验与工具

    private static String requireKind(String kind)
    {
        if (KIND_VIDEO.equals(kind) || KIND_PHOTO.equals(kind))
        {
            return kind;
        }
        throw MediaException.badRequest("kind 必须是 video 或 photo");
    }

    private static String requireText(String value, String message)
    {
        if (StringUtils.isEmpty(value))
        {
            throw MediaException.badRequest(message);
        }
        return value;
    }

    private static Integer requireSeq(Integer seq)
    {
        if (seq == null || seq < 0)
        {
            throw MediaException.badRequest("seq 必须是 >=0 整数");
        }
        return seq;
    }

    private static String str(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** JSON 里可能是 Integer/Long/String，统一成 Integer；非整数返回 null 交给校验报 400 */
    private static Integer intOf(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            double d = ((Number) value).doubleValue();
            if (d != Math.floor(d))
            {
                return null;
            }
            return (int) d;
        }
        String text = String.valueOf(value).trim();
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static Double doubleOf(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return positiveOrZero(((Number) value).doubleValue());
        }
        try
        {
            return positiveOrZero(Double.parseDouble(String.valueOf(value).trim()));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static Double positiveOrZero(double value)
    {
        return Double.isFinite(value) && value > 0 ? value : 0D;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String field)
    {
        if (!(value instanceof Map))
        {
            throw MediaException.badRequest(field + " 必须是对象");
        }
        return (Map<String, Object>) value;
    }

    private static List<Object> asList(Object value, String field)
    {
        if (!(value instanceof List))
        {
            throw MediaException.badRequest(field + " 必须是数组");
        }
        List<Object> out = new ArrayList<>();
        for (Object item : (List<?>) value)
        {
            out.add(item);
        }
        return out;
    }

    /** 按码点截断，避免把 emoji 切坏 */
    private static String clip(String value, int max)
    {
        if (value == null)
        {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= max)
        {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    private Object lock(String ownerKey)
    {
        int hash = (ownerKey.hashCode() & 0x7fffffff) % LOCK_STRIPES;
        return writeLocks[hash];
    }
}
