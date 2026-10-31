package com.qtools.media.controller;

import com.qtools.common.exception.ServiceException;
import com.qtools.media.service.IMediaIndexService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内容审核（P6.35）：新扫描 / 新导入的相册与视频默认 pending，站端不展示；
 * 审核通过（approved）之后才按分级对访客可见，驳回（rejected）永不展示、后台保留。
 *
 * <p>粒度按「相册 / 视频」，照片不单独设状态（继承所属相册）。</p>
 *
 * @author qtools
 */
@RestController
@RequestMapping("/media/admin/review")
public class MediaReviewController
{
    private static final String[] STATES = { "pending", "approved", "rejected" };

    @Autowired
    private DataSource dataSource;

    /** P6.37：审核状态变化后让索引快照立即失效（否则站端最多 60 秒才看到结果） */
    @Autowired
    private IMediaIndexService mediaIndexService;

    /** 顶部统计：{album:{pending,approved,rejected}, video:{...}} */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/summary")
    public Map<String, Object> summary()
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("album", countByState(jdbc, "media_album"));
        out.put("video", countByState(jdbc, "media_video"));
        return out;
    }

    /**
     * 队列列表：type=album|video，state=pending|approved|rejected（空=全部），按时间倒序。
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(value = "type", required = false, defaultValue = "album") String type,
            @RequestParam(value = "state", required = false, defaultValue = "pending") String state,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size,
            // P6.55：入站队列口径
            //   suggest（默认）= 只有建议册（confirmed=0），它是"新到货"，不该被审核积压灌满
            //   pending = 未审的正式册（历史积压，单独看、单独清）
            //   all     = 两者并集
            @RequestParam(value = "inbound", required = false) String inbound)
    {
        String kind = normalizeType(type);
        String stateValue = normalizeState(state, true);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        String kw = keyword == null ? "" : keyword.trim();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // P6.55：入站队列口径（suggest / pending / all）；它替代 state 筛选，两者不同时生效
        String inboundMode = inbound == null ? null : inbound.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(inboundMode))
        {
            inboundMode = "suggest";
        }
        boolean inboundOnly = inboundMode != null && !inboundMode.isEmpty() && "album".equals(kind);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (inboundOnly)
        {
            if ("pending".equals(inboundMode))
            {
                where.append(" and t.review_state = 'pending'");
            }
            else if ("all".equals(inboundMode))
            {
                where.append(" and (t.confirmed = 0 or t.review_state = 'pending')");
            }
            else
            {
                // 默认：只列还没被采纳的建议册
                where.append(" and t.confirmed = 0");
            }
        }
        else if (stateValue != null)
        {
            where.append(" and t.review_state = ?");
            args.add(stateValue);
        }
        if (!kw.isEmpty())
        {
            where.append(" and (t.title like ? or t.dir like ?)");
            args.add("%" + kw + "%");
            args.add("%" + kw + "%");
        }
        String base = "album".equals(kind) ? ALBUM_FROM : VIDEO_FROM;
        Long total = jdbc.queryForObject("select count(*) from " + base + where, Long.class, args.toArray());
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select " + ("album".equals(kind) ? ALBUM_COLS : VIDEO_COLS) + " from " + base + where
                        + " order by t.mtime desc, t.id asc limit ? offset ?", args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total == null ? 0 : total.intValue());
        out.put("page", safePage);
        out.put("size", safeSize);
        out.put("type", kind);
        out.put("state", stateValue);
        return out;
    }

    /**
     * 批量改审核状态：body {type:"album"|"video", ids:[...], state:"approved"|"rejected"|"pending"}。
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/state")
    public Map<String, Object> updateState(@RequestBody Map<String, Object> body)
    {
        String kind = normalizeType(text(body.get("type")));
        String state = normalizeState(text(body.get("state")), false);
        List<Object> ids = ids(body.get("ids"));
        if (ids.isEmpty())
        {
            throw new ServiceException("缺少 ids");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            in.append(i == 0 ? "?" : ",?");
        }
        List<Object> args = new ArrayList<>();
        args.add(state);
        args.addAll(ids);
        int updated = jdbc.update("update " + ("album".equals(kind) ? "media_album" : "media_video")
                + " set review_state = ? where id in (" + in + ")", args.toArray());
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("type", kind);
        out.put("state", state);
        return out;
    }

    /**
     * 入站队列的「通过并采纳」（P6.55）：body {ids:[albumId...]}
     *
     * <p>把审校与策展这两个决定合成一次点击：{@code review_state=approved} + {@code confirmed=1}。
     * 想分开做的人仍然可以用 {@code /state} 与 {@code /albums/confirm} 两个接口。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/inbound/approve")
    public Map<String, Object> approveInbound(@RequestBody Map<String, Object> body)
    {
        List<Object> ids = ids(body.get("ids"));
        if (ids.isEmpty())
        {
            throw new ServiceException("缺少 ids");
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            in.append(i == 0 ? "?" : ",?");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int updated = jdbc.update("update media_album set review_state = 'approved', confirmed = 1"
                + " where id in (" + in + ")", ids.toArray());
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approved", updated);
        out.put("ids", ids);
        return out;
    }

    /**
     * 分级复核列表：模型判不了（auto_rating='review'）的照片，按相册聚合展示。
     * 返回：{rows:[{id, albumId, albumTitle, seq, autoPorn, contentRating}], total}
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/rating/list")
    public Map<String, Object> ratingList(@RequestParam(value = "albumId", required = false) String albumId,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "60") int size)
    {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringBuilder where = new StringBuilder(" where p.auto_rating = 'review'");
        List<Object> args = new ArrayList<>();
        if (albumId != null && !albumId.trim().isEmpty())
        {
            where.append(" and p.album_id = ?");
            args.add(albumId.trim());
        }
        Long total = jdbc.queryForObject(
                "select count(*) from media_photo p" + where, Long.class, args.toArray());
        args.add(safeSize);
        args.add((safePage - 1) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select p.id, p.album_id as albumId, p.seq, p.auto_porn as autoPorn,"
                        + " p.content_rating as contentRating, a.title as albumTitle, a.review_state as albumState"
                        + " from media_photo p join media_album a on a.id = p.album_id" + where
                        + " order by a.title asc, p.seq asc limit ? offset ?", args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total == null ? 0 : total.intValue());
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /**
     * 人工定档（分级复核）：body {ids:[...], rating:"general"|"guidance"|"restricted"}。
     * 同时把 auto_rating 改成人工档位，这张就从复核队列里消失。
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/rating/decide")
    public Map<String, Object> ratingDecide(@RequestBody Map<String, Object> body)
    {
        String rating = text(body.get("rating"));
        String value = rating == null ? "" : rating.trim().toLowerCase(Locale.ROOT);
        if (!"general".equals(value) && !"guidance".equals(value) && !"restricted".equals(value))
        {
            throw new ServiceException("rating 只能是 general / guidance / restricted");
        }
        List<Object> ids = ids(body.get("ids"));
        if (ids.isEmpty())
        {
            throw new ServiceException("缺少 ids");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            in.append(i == 0 ? "?" : ",?");
        }
        List<Object> args = new ArrayList<>();
        args.add(value);
        args.add(value);
        args.addAll(ids);
        int updated = jdbc.update("update media_photo set content_rating = ?, auto_rating = ? where id in (" + in + ")",
                args.toArray());
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("rating", value);
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static final String ALBUM_FROM = "media_album t";

    private static final String ALBUM_COLS = "t.id, t.title, t.dir, t.photo_count as count, t.bytes, t.cover_path as cover,"
            + " t.content_rating as contentRating, t.review_state as reviewState, t.confirmed as confirmed, t.mtime,"
            + " (select group_concat(p.name order by p.id separator ' / ') from media_album_person ap"
            + "   join media_person p on p.id = ap.person_id where ap.album_id = t.id) as persons,"
            + " (select group_concat(ap.person_id order by ap.person_id) from media_album_person ap"
            + "   where ap.album_id = t.id) as personIds,"
            + " (select group_concat(distinct p2.name) from media_person p2 where p2.id = t.person_id) as primaryPerson";

    private static final String VIDEO_FROM = "media_video t";

    private static final String VIDEO_COLS = "t.id, t.title, t.folder as dir, t.size as bytes, t.poster_path as poster,"
            + " t.rel_path as relPath, t.dur, t.w, t.h, t.content_rating as contentRating,"
            + " t.review_state as reviewState, t.mtime,"
            + " (select group_concat(a.name order by a.name separator ' / ') from media_video_actor a"
            + "   where a.video_id = t.id) as persons,"
            + " (select group_concat(a.person_id order by a.person_id) from media_video_actor a"
            + "   where a.video_id = t.id) as personIds";

    private static Map<String, Object> countByState(JdbcTemplate jdbc, String table)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String state : STATES)
        {
            out.put(state, 0);
        }
        for (Map<String, Object> row : jdbc.queryForList(
                "select review_state st, count(*) n from " + table + " group by review_state"))
        {
            out.put(String.valueOf(row.get("st")), row.get("n"));
        }
        return out;
    }

    private static String normalizeType(String type)
    {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!"album".equals(value) && !"video".equals(value))
        {
            throw new ServiceException("type 只能是 album / video");
        }
        return value;
    }

    /** state 允许空（列表=全部）；写状态时必须三选一 */
    private static String normalizeState(String state, boolean allowEmpty)
    {
        String value = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() && allowEmpty)
        {
            return null;
        }
        for (String candidate : STATES)
        {
            if (candidate.equals(value))
            {
                return value;
            }
        }
        throw new ServiceException("state 只能是 pending / approved / rejected");
    }

    private static String text(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static List<Object> ids(Object raw)
    {
        List<Object> out = new ArrayList<>();
        if (raw instanceof List<?> list)
        {
            for (Object item : list)
            {
                if (item != null)
                {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}





