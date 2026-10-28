package com.qtools.media.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRatingMapper;
import com.qtools.media.service.IMediaGuestAccessService;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaRatingService;
import com.qtools.media.support.MediaRating;

/**
 * 内容分级服务实现（P5.99）
 *
 * <p><b>两个缓存失效点</b>：每次写入（单条 / 批量 / 策略）之后都必须同时</p>
 * <ol>
 *   <li>{@link IMediaGuestAccessService#invalidateRoots()} —— 访客可见性快照（根 + 分级策略，TTL 5s）；</li>
 *   <li>{@link IMediaIndexService#invalidateSnapshot()} —— {@code /api/media/index} 的未登录 / 登录两份索引（TTL 60s）。</li>
 * </ol>
 * <p>少任何一个的表现都是「管理端改完，站点却还是旧结果」，而 TTL 之内最久要等 60 秒。</p>
 *
 * @author qtools
 */
@Service
public class MediaRatingServiceImpl implements IMediaRatingService
{
    private static final Logger log = LoggerFactory.getLogger(MediaRatingServiceImpl.class);

    /** 批量接口一次最多处理多少个 id（防止一个误粘贴把整张表刷成同一档） */
    private static final int BATCH_MAX = 2000;

    @Autowired
    private MediaRatingMapper mediaRatingMapper;

    /** 访客可见性快照：改分级 / 改策略后必须立刻失效，否则未登录最多 5s 还是旧口径 */
    @Autowired
    private IMediaGuestAccessService mediaGuestAccessService;

    /** 索引快照：分级决定条目在不在索引里，同样要立刻失效 */
    @Autowired
    private IMediaIndexService mediaIndexService;

    // ------------------------------------------------------------------ 分级写入

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateVideoRating(String videoId, String rating)
    {
        String id = requireId(videoId, "videoId");
        String value = MediaRating.parse(rating);
        int affected = mediaRatingMapper.updateVideoRating(id, value);
        if (affected == 0 && mediaRatingMapper.countVideosByIds(List.of(id)) == 0)
        {
            throw MediaException.notFound("视频不存在: " + id);
        }
        afterWrite("video", id, value);
        return node("video", id, value);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAlbumRating(String albumId, String rating)
    {
        String id = requireId(albumId, "albumId");
        String value = MediaRating.parse(rating);
        int affected = mediaRatingMapper.updateAlbumRating(id, value);
        if (affected == 0 && mediaRatingMapper.countAlbumsByIds(List.of(id)) == 0)
        {
            throw MediaException.notFound("相册不存在: " + id);
        }
        afterWrite("album", id, value);
        return node("album", id, value);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batch(String type, List<String> ids, String rating)
    {
        String kind = normalizeType(type);
        String value = MediaRating.parse(rating);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (ids != null)
        {
            for (String id : ids)
            {
                if (StringUtils.isNotEmpty(id) && StringUtils.isNotEmpty(id.trim()))
                {
                    unique.add(id.trim());
                }
            }
        }
        if (unique.isEmpty())
        {
            throw MediaException.badRequest("ids 不能为空（要改哪些条目）");
        }
        if (unique.size() > BATCH_MAX)
        {
            throw MediaException.badRequest("ids 一次最多 " + BATCH_MAX + " 个，本次: " + unique.size());
        }
        List<String> targets = new ArrayList<>(unique);
        long matched = "album".equals(kind) ? mediaRatingMapper.countAlbumsByIds(targets)
                : mediaRatingMapper.countVideosByIds(targets);
        int updated = "album".equals(kind) ? mediaRatingMapper.updateAlbumsRating(targets, value)
                : mediaRatingMapper.updateVideosRating(targets, value);
        afterWrite(kind, unique.size() + " ids", value);
        Map<String, Object> out = node(kind, null, value);
        out.put("requested", targets.size());
        out.put("matched", matched);
        out.put("updated", updated);
        log.info("批量改内容分级 kind={} requested={} matched={} updated={} rating={}", kind, targets.size(),
                matched, updated, value);
        return out;
    }

    private static String normalizeType(String type)
    {
        String text = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        if ("video".equals(text) || "videos".equals(text))
        {
            return "video";
        }
        if ("album".equals(text) || "albums".equals(text))
        {
            return "album";
        }
        throw MediaException.badRequest("type 只能是 video 或 album，本次: " + type);
    }

    private static String requireId(String id, String field)
    {
        String text = id == null ? "" : id.trim();
        if (text.isEmpty())
        {
            throw MediaException.badRequest("missing " + field);
        }
        return text;
    }

    private static Map<String, Object> node(String kind, String id, String rating)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        if (id != null)
        {
            out.put("id", id);
        }
        out.put("rating", rating);
        out.put("label", MediaRating.labelOf(rating));
        return out;
    }

    /** 写分级之后：两份缓存一起失效（见类注释） */
    private void afterWrite(String kind, Object target, String rating)
    {
        mediaGuestAccessService.invalidateRoots();
        mediaIndexService.invalidateSnapshot();
        log.info("内容分级已更新 kind={} target={} rating={}", kind, target, rating);
    }

    // ------------------------------------------------------------------ 策略

    @Override
    public Map<String, Object> policy()
    {
        Map<String, Boolean> known = new LinkedHashMap<>();
        for (String value : MediaRating.values())
        {
            // 缺行 = 未配置 → 与迁移预置的默认值一致（未登录可见）
            known.put(value, Boolean.TRUE);
        }
        List<Map<String, Object>> rows = mediaRatingMapper.selectPolicyList();
        if (rows != null)
        {
            for (Map<String, Object> row : rows)
            {
                String value = MediaRating.normalize(stringOf(row.get("rating")));
                known.put(value, !isZero(row.get("guestVisible")));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : known.entrySet())
        {
            out.put(entry.getKey(), entry.getValue());
        }
        List<Map<String, Object>> items = new ArrayList<>(known.size());
        for (Map.Entry<String, Boolean> entry : known.entrySet())
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", entry.getKey());
            item.put("label", MediaRating.labelOf(entry.getKey()));
            item.put("guestVisible", entry.getValue());
            items.add(item);
        }
        out.put("items", items);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updatePolicy(Boolean general, Boolean guidance, Boolean restricted)
    {
        boolean any = general != null || guidance != null || restricted != null;
        if (!any)
        {
            throw MediaException.badRequest("至少要给一项：general / guidance / restricted（true=未登录可见）");
        }
        applyPolicy(MediaRating.GENERAL, general);
        applyPolicy(MediaRating.GUIDANCE, guidance);
        applyPolicy(MediaRating.RESTRICTED, restricted);
        mediaGuestAccessService.invalidateRoots();
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = policy();
        log.info("内容分级可见性策略已更新 general={} guidance={} restricted={}", general, guidance, restricted);
        log.debug("最新策略: {}", out);
        return out;
    }

    /** 逐项局部更新：{@code null} = 不动这一档；行不存在（被删过）时补一行，行为与迁移预置一致 */
    private void applyPolicy(String rating, Boolean guestVisible)
    {
        if (guestVisible == null)
        {
            return;
        }
        int value = guestVisible.booleanValue() ? 1 : 0;
        int affected = mediaRatingMapper.updatePolicy(rating, Integer.valueOf(value));
        if (affected == 0 && isMissingPolicyRow(rating))
        {
            mediaRatingMapper.insertPolicy(rating, Integer.valueOf(value), MediaRating.labelOf(rating));
        }
    }

    private boolean isMissingPolicyRow(String rating)
    {
        List<Map<String, Object>> rows = mediaRatingMapper.selectPolicyList();
        if (rows == null)
        {
            return true;
        }
        for (Map<String, Object> row : rows)
        {
            if (rating.equals(MediaRating.normalize(stringOf(row.get("rating")))))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isZero(Object value)
    {
        if (value instanceof Number number)
        {
            return number.intValue() == 0;
        }
        String text = stringOf(value);
        return "0".equals(text) || "false".equalsIgnoreCase(text);
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
}
