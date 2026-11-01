package com.qtools.media.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.enums.BusinessType;
import com.qtools.common.exception.ServiceException;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.support.MediaPhash;
import com.qtools.media.support.MediaPhotoRemover;

/**
 * 「相似图片」（感知指纹分组）——P6.77
 *
 * <p>与「同内容多份」的区别：那个看 content_hash（文件字节完全相同），只能抓逐字节重复；
 * 这个看 64 位 dHash 的汉明距离，能抓<b>同一张图被重新压缩/缩放</b>的情况
 * （实测有 142 字节差、视觉完全一致的一对，前者永远抓不到）。</p>
 *
 * <p><b>绝不自动删</b>：写真连拍/同场景换姿势的两张，指纹也会很近。所以所有清理动作都要求
 * 明确的范围（某一组 / 本页 / 全部）与一次人工确认，删除走 {@link MediaPhotoRemover}
 * （文件进回收站 + 删行，可恢复）。</p>
 */
@RestController
@RequestMapping("/media/admin/duplicates/near")
public class MediaDuplicateNearController
{
    private static final Logger log = LoggerFactory.getLogger(MediaDuplicateNearController.class);

    /** 阈值上限：再往上就分不清"同一张"和"同一套写真"了 */
    private static final int MAX_THRESHOLD = 6;

    private static final int MAX_SIZE = 50;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MediaPhotoRemover photoRemover;

    @Autowired
    private MediaProperties mediaProperties;

    /** 分组结果缓存（阈值 → 分组）；任何删除/重建都整体失效 */
    private final Map<Integer, List<List<Map<String, Object>>>> cache = new HashMap<>();

    /**
     * 任何路径删掉一条照片行 → 分组缓存立刻作废（P6.78.1）
     *
     * <p>少了这一步就会出现"清理完那项还在、再点一次报「照片不存在」"：
     * 删行是别的入口干的，而这个缓存还在原地发旧分组。</p>
     */
    @org.springframework.context.event.EventListener
    public void onPhotoRemoved(com.qtools.media.support.MediaPhotoRemovedEvent event)
    {
        cache.clear();
    }

    /**
     * 指纹覆盖情况：{@code {total, hashed, remaining}}
     *
     * <p>页面据此决定要不要提示「先算指纹」；不算指纹就没有分组，因为没有任何判定依据。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/status")
    public Map<String, Object> status()
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer total = jdbc.queryForObject("select count(*) from media_photo", Integer.class);
        Integer hashed = jdbc.queryForObject("select count(*) from media_photo where phash is not null", Integer.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total.intValue());
        out.put("hashed", hashed == null ? 0 : hashed.intValue());
        out.put("remaining", Math.max(0, (total == null ? 0 : total.intValue()) - (hashed == null ? 0 : hashed.intValue())));
        return out;
    }

    /**
     * 增量补算指纹：{@code POST /near/build {limit}}
     *
     * <p>只算 {@code phash is null} 的行；前端循环调用直到 {@code remaining=0}。
     * 不放在一次请求里跑完：全库 9443 张要 2-3 分钟，HTTP 会超时。</p>
     */
    @PreAuthorize("@ss.hasAnyPermi('media:admin:scan,media:admin:edit')")
    @Log(title = "媒体库-相似指纹", businessType = BusinessType.OTHER)
    @PostMapping("/build")
    public Map<String, Object> build(@RequestBody(required = false) Map<String, Object> body)
    {
        int limit = intOf(body, "limit", 400);
        limit = Math.max(1, Math.min(4000, limit));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, content_hash from media_photo where phash is null and content_hash is not null limit " + limit);
        Path thumbs = thumbsDir();
        int ok = 0;
        int skipped = 0;
        if (!rows.isEmpty())
        {
            int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Object[]>> tasks = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows)
            {
                final long id = ((Number) row.get("id")).longValue();
                final String hash = String.valueOf(row.get("content_hash"));
                tasks.add(pool.submit(() -> {
                    Path file = MediaPhash.thumbOf(thumbs, hash);
                    Long code = file == null ? null : MediaPhash.of(file);
                    return new Object[] { Long.valueOf(id), code };
                }));
            }
            pool.shutdown();
            try
            {
                pool.awaitTermination(5, TimeUnit.MINUTES);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            for (Future<Object[]> f : tasks)
            {
                try
                {
                    Object[] r = f.get();
                    if (r[1] == null)
                    {
                        skipped++;
                        continue;
                    }
                    jdbc.update("update media_photo set phash = ? where id = ?", r[1], r[0]);
                    ok++;
                }
                catch (Exception e)
                {
                    skipped++;
                }
            }
        }
        cache.clear();
        Map<String, Object> out = status();
        out.put("scanned", Integer.valueOf(rows.size()));
        out.put("hashedNow", Integer.valueOf(ok));
        out.put("skipped", Integer.valueOf(skipped));
        log.info("相似指纹：本次 {} 张，写入 {}，跳过 {}，剩余 {}", rows.size(), ok, skipped, out.get("remaining"));
        return out;
    }

    /**
     * 相似分组：{@code GET /near/groups?threshold=0&page=1&size=10}
     *
     * <p>threshold 是汉明距离上限：0 = 指纹完全相同（最保守，误判极少），
     * 1-2 会开始混进连拍/同场景的照片，3 以上基本等于"同一套写真"，不建议用于清理。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/groups")
    public Map<String, Object> groups(@RequestParam(value = "threshold", required = false, defaultValue = "0") int threshold,
            @RequestParam(value = "mode", required = false, defaultValue = "cross") String mode,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "10") int size)
    {
        int th = Math.max(0, Math.min(MAX_THRESHOLD, threshold));
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(MAX_SIZE, size));
        List<List<Map<String, Object>>> all = filterByMode(grouped(th), mode);
        int total = all.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", Integer.valueOf(th));
        out.put("mode", normalizeMode(mode));
        out.put("total", Integer.valueOf(total));
        out.put("page", Integer.valueOf(p));
        out.put("size", Integer.valueOf(s));
        int from = Math.min(total, (p - 1) * s);
        int to = Math.min(total, from + s);
        out.put("rows", all.subList(from, to));
        return out;
    }

    /**
     * 批量清理：{@code POST /near/clean {threshold, keepRule, groupKeys?}}
     *
     * <p>{@code groupKeys} 省略 = 该阈值下<b>全部分组</b>；显式给出则只处理这几组
     * （前端"本页全部清理"用它，避免把没看过的页一起动掉）。</p>
     *
     * <p>{@code keepRule}：{@code largest}（默认，保留体积最大的那份——通常是未压缩的原图）
     * 或 {@code earliest}（保留最早进库的那份）。每组保留一份，其余全部清理。</p>
     */
    @PreAuthorize("@ss.hasAnyPermi('media:admin:scan,media:admin:edit')")
    @Log(title = "媒体库-清理相似图片", businessType = BusinessType.DELETE)
    @PostMapping("/clean")
    public Map<String, Object> clean(@RequestBody Map<String, Object> body)
    {
        int th = Math.max(0, Math.min(MAX_THRESHOLD, intOf(body, "threshold", 0)));
        String keepRule = body.get("keepRule") == null ? "largest" : String.valueOf(body.get("keepRule")).trim();
        List<String> keys = keysOf(body.get("groupKeys"));
        String mode = body.get("mode") == null ? "cross" : String.valueOf(body.get("mode")).trim();
        List<List<Map<String, Object>>> all = filterByMode(grouped(th), mode);
        List<Map<String, Object>> removedRows = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        int groups = 0;
        for (List<Map<String, Object>> group : all)
        {
            String key = String.valueOf(group.get(0).get("groupKey"));
            if (keys != null && !keys.contains(key))
            {
                continue;
            }
            groups++;
            Map<String, Object> keep = pickKeep(group, keepRule);
            for (Map<String, Object> m : group)
            {
                if (m == keep)
                {
                    continue;
                }
                try
                {
                    removedRows.add(photoRemover.remove(((Number) m.get("id")).longValue()));
                }
                catch (RuntimeException e)
                {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("id", m.get("id"));
                    f.put("relPath", m.get("relPath"));
                    f.put("reason", e.getMessage());
                    failed.add(f);
                }
            }
        }
        cache.clear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", Integer.valueOf(groups));
        out.put("mode", normalizeMode(mode));
        out.put("removed", Integer.valueOf(removedRows.size()));
        out.put("failed", failed);
        out.put("removedRows", removedRows);
        log.info("相似图片清理：阈值 {} 规则 {} 组 {} 删除 {} 失败 {}", th, keepRule, groups, removedRows.size(),
                failed.size());
        return out;
    }

    /** 单组：只留指定的那一份，其余清理：{@code POST /near/keep {keepId, memberIds}} */
    @PreAuthorize("@ss.hasAnyPermi('media:admin:scan,media:admin:edit')")
    @Log(title = "媒体库-清理相似图片", businessType = BusinessType.DELETE)
    @PostMapping("/keep")
    public Map<String, Object> keep(@RequestBody Map<String, Object> body)
    {
        Long keepId = longOf(body.get("keepId"));
        List<String> ids = keysOf(body.get("memberIds"));
        if (keepId == null || ids == null || ids.isEmpty())
        {
            throw new ServiceException("缺少 keepId / memberIds");
        }
        List<Map<String, Object>> removedRows = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String raw : ids)
        {
            long id = Long.parseLong(raw);
            if (id == keepId.longValue())
            {
                continue;
            }
            try
            {
                removedRows.add(photoRemover.remove(id));
            }
            catch (RuntimeException e)
            {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", Long.valueOf(id));
                f.put("reason", e.getMessage());
                failed.add(f);
            }
        }
        cache.clear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keptId", keepId);
        out.put("removed", Integer.valueOf(removedRows.size()));
        out.put("failed", failed);
        log.info("相似图片：组内保留 {} 删除 {} 失败 {}", keepId, removedRows.size(), failed.size());
        return out;
    }

    /**
     * 清理指定的若干份：{@code POST /near/remove {ids:[...]}}
     *
     * <p>给"勾选清理"和"清理某一本册在本组里的全部"用：前端算好要删的 id 列表，
     * 一次请求删完（仍然一次人工确认），不需要为每种玩法各开一个接口。</p>
     */
    @PreAuthorize("@ss.hasAnyPermi('media:admin:scan,media:admin:edit')")
    @Log(title = "媒体库-清理相似图片", businessType = BusinessType.DELETE)
    @PostMapping("/remove")
    public Map<String, Object> remove(@RequestBody Map<String, Object> body)
    {
        List<String> ids = keysOf(body.get("ids"));
        if (ids == null || ids.isEmpty())
        {
            throw new ServiceException("缺少 ids");
        }
        List<Map<String, Object>> removedRows = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String raw : ids)
        {
            long id;
            try
            {
                id = Long.parseLong(raw);
            }
            catch (NumberFormatException e)
            {
                continue;
            }
            try
            {
                removedRows.add(photoRemover.remove(id));
            }
            catch (RuntimeException e)
            {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", Long.valueOf(id));
                f.put("reason", e.getMessage());
                failed.add(f);
            }
        }
        cache.clear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", Integer.valueOf(removedRows.size()));
        out.put("failed", failed);
        log.info("相似图片：按 id 清理 {} 张，失败 {}", removedRows.size(), failed.size());
        return out;
    }

    // ------------------------------------------------------------------ 内部

    /** 按阈值分组（带缓存）：同一候选集只算一次，页面翻页不重复做 O(n²) */
    private List<List<Map<String, Object>>> grouped(int th)
    {
        List<List<Map<String, Object>>> cached = cache.get(Integer.valueOf(th));
        if (cached != null)
        {
            return cached;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select p.id as id, p.phash as phash, p.album_id as albumId, p.seq as seq, p.rel_path as relPath,"
                        + " coalesce(p.bytes,0) as bytes, p.content_hash as contentHash, a.title as albumTitle"
                        + " from media_photo p left join media_album a on a.id = p.album_id"
                        + " where p.phash is not null order by p.album_id, p.seq");
        int n = rows.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++)
        {
            parent[i] = i;
        }
        long[] codes = new long[n];
        for (int i = 0; i < n; i++)
        {
            codes[i] = ((Number) rows.get(i).get("phash")).longValue();
        }
        for (int i = 0; i < n; i++)
        {
            for (int j = i + 1; j < n; j++)
            {
                if (MediaPhash.distance(codes[i], codes[j]) <= th)
                {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Map<String, Object>>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
        {
            byRoot.computeIfAbsent(Integer.valueOf(find(parent, i)), k -> new ArrayList<>()).add(rows.get(i));
        }
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        for (List<Map<String, Object>> g : byRoot.values())
        {
            if (g.size() < 2)
            {
                continue;
            }
            // 组键 = 组内第一条的 content_hash（稳定、可回传给 clean）
            Map<String, Object> first = g.get(0);
            java.util.Set<String> albums = new java.util.LinkedHashSet<>();
            for (Map<String, Object> m : g)
            {
                albums.add(String.valueOf(m.get("albumId")));
                m.put("groupKey", first.get("contentHash"));
                m.put("thumb", m.get("contentHash") == null ? null
                        : "/api/media/thumb/p8/" + String.valueOf(m.get("contentHash")));
                m.put("preview", m.get("contentHash") == null ? null
                        : "/api/media/preview/p/" + String.valueOf(m.get("contentHash")));
            }
            // 组内按「册 → 序号」排：同册的挨在一起，一眼看出"是这本册里的一组连拍"
            // 还是"另外一本册里也有同一张"。保留哪份由 pickKeep 决定，与显示顺序无关。
            g.sort((a, b) -> {
                int c = String.valueOf(a.get("albumId")).compareTo(String.valueOf(b.get("albumId")));
                if (c != 0)
                {
                    return c;
                }
                return Long.compare(((Number) a.get("seq")).longValue(), ((Number) b.get("seq")).longValue());
            });
            for (Map<String, Object> m : g)
            {
                m.put("albumCount", Integer.valueOf(albums.size()));
                m.put("crossAlbum", Boolean.valueOf(albums.size() > 1));
            }
            groups.add(g);
        }
        groups.sort((a, b) -> {
            // P6.78：跨册组优先。同册内的相似多半是连拍/同场景换姿势（写真里极常见），
            // 跨册的相似才更可能是"同一张图被重新压缩后放进了另一本册"。
            boolean ca = Boolean.TRUE.equals(a.get(0).get("crossAlbum"));
            boolean cb = Boolean.TRUE.equals(b.get(0).get("crossAlbum"));
            if (ca != cb)
            {
                return ca ? -1 : 1;
            }
            int c = Integer.compare(b.size(), a.size());
            if (c != 0)
            {
                return c;
            }
            long ab = ((Number) a.get(0).get("bytes")).longValue();
            long bb = ((Number) b.get(0).get("bytes")).longValue();
            return Long.compare(bb, ab);
        });
        cache.put(Integer.valueOf(th), groups);
        return groups;
    }

    /** mode：cross=只看跨册（默认，更可能是真重复）/ same=只看同册（多半是连拍）/ all=全部 */
    private static String normalizeMode(String mode)
    {
        String m = mode == null ? "cross" : mode.trim().toLowerCase();
        return "same".equals(m) || "all".equals(m) ? m : "cross";
    }

    private static List<List<Map<String, Object>>> filterByMode(List<List<Map<String, Object>>> all, String mode)
    {
        String m = normalizeMode(mode);
        if ("all".equals(m))
        {
            return all;
        }
        boolean wantCross = "cross".equals(m);
        List<List<Map<String, Object>>> out = new ArrayList<>();
        for (List<Map<String, Object>> g : all)
        {
            boolean cross = Boolean.TRUE.equals(g.get(0).get("crossAlbum"));
            if (cross == wantCross)
            {
                out.add(g);
            }
        }
        return out;
    }

    private static int find(int[] parent, int x)
    {
        while (parent[x] != x)
        {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b)
    {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb)
        {
            parent[rb] = ra;
        }
    }

    /** 组内保留哪一份：largest=体积最大（通常最接近原图）；earliest=最早进库 */
    private static Map<String, Object> pickKeep(List<Map<String, Object>> group, String keepRule)
    {
        if ("earliest".equalsIgnoreCase(keepRule))
        {
            Map<String, Object> best = null;
            for (Map<String, Object> m : group)
            {
                if (best == null)
                {
                    best = m;
                    continue;
                }
                int c = String.valueOf(m.get("albumId")).compareTo(String.valueOf(best.get("albumId")));
                long ms = ((Number) m.get("seq")).longValue();
                long bs = ((Number) best.get("seq")).longValue();
                if (c < 0 || (c == 0 && ms < bs))
                {
                    best = m;
                }
            }
            return best == null ? group.get(0) : best;
        }
        Map<String, Object> best = group.get(0);
        for (Map<String, Object> m : group)
        {
            if (((Number) m.get("bytes")).longValue() > ((Number) best.get("bytes")).longValue())
            {
                best = m;
            }
        }
        return best;
    }

    private Path thumbsDir()
    {
        String configured = mediaProperties.getThumbsPath();
        if (configured == null || configured.trim().isEmpty())
        {
            throw new ServiceException("未配置 qtools.media.thumbs-path，无法定位缩略图目录");
        }
        return java.nio.file.Paths.get(configured.trim());
    }

    private static int intOf(Map<String, Object> body, String key, int fallback)
    {
        if (body == null || body.get(key) == null)
        {
            return fallback;
        }
        try
        {
            return Integer.parseInt(String.valueOf(body.get(key)).trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private static Long longOf(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        try
        {
            return Long.valueOf(String.valueOf(raw).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static List<String> keysOf(Object raw)
    {
        if (raw == null)
        {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof List)
        {
            for (Object o : (List<?>) raw)
            {
                if (o != null && !String.valueOf(o).trim().isEmpty())
                {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty())
        {
            return null;
        }
        for (String part : s.split(","))
        {
            if (!part.trim().isEmpty())
            {
                out.add(part.trim());
            }
        }
        return out;
    }
}
