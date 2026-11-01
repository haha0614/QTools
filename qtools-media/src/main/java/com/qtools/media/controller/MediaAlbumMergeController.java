package com.qtools.media.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qtools.common.exception.ServiceException;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.support.MediaPhotoRemover;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 册归属（P6.48）：把一本册整体并入另一本。
 *
 * <p>为什么需要它：扫描按「一个目录 = 一本册」工作，子目录（比如马吉册下的 123 目录）会自成一册，
 * 而管理端原本没有任何手段把它并回去。</p>
 *
 * <p>做法：</p>
 * <ol>
 *   <li>照片改挂到目标册（照片身份是 root_id + rel_path，所以重扫不会把它们又拽回去）；</li>
 *   <li>在 media_album_dir 里登记「源目录 → 目标册」，扫描时该目录不再单独建册；</li>
 *   <li>删掉源册，重算目标册的计数。</li>
 * </ol>
 */
@RestController
@RequestMapping("/media/admin/albums")
public class MediaAlbumMergeController
{
    private static final Logger log = LoggerFactory.getLogger(MediaAlbumMergeController.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IMediaIndexService mediaIndexService;

    @Autowired
    private MediaPhotoRemover photoRemover;

    /**
     * 并入：body {source:"aXXX", target:"aYYY"}。
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/merge")
    public Map<String, Object> merge(@RequestBody Map<String, Object> body)
    {
        String source = text(body.get("source"));
        String target = text(body.get("target"));
        if (source == null || source.isEmpty() || target == null || target.isEmpty())
        {
            throw new ServiceException("缺少 source / target");
        }
        if (source.equals(target))
        {
            throw new ServiceException("不能并入自己");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> src = album(jdbc, source);
        Map<String, Object> dst = album(jdbc, target);
        if (src == null || dst == null)
        {
            throw new ServiceException("册不存在");
        }
        Integer rootId = dominantRoot(jdbc, source);
        // P6.56：并进一本已上站的册，搬过去的照片单独过一遍审核（同上，触发器只覆盖 INSERT）
        if (isLiveAlbum(jdbc, target))
        {
            jdbc.update("update media_photo set review_state = 'pending' where album_id = ?"
                    + " and (review_state is null or review_state = 'approved')", source);
        }
        int offset = nextSeq(jdbc, target);
        int moved = jdbc.update("update media_photo set album_id = ?, seq = seq + ? where album_id = ?",
                target, offset, source);
        // 目录别名：源目录以后归目标册（重扫不会再建出源册）
        if (rootId != null)
        {
            String dir = String.valueOf(src.get("dir"));
            jdbc.update("insert into media_album_dir (album_id, root_id, dir) values (?, ?, ?)"
                    + " on duplicate key update album_id = ?", target, rootId, dir, target);
        }
        // 原本指向源册的别名改指目标册，避免悬空
        jdbc.update("update media_album_dir set album_id = ? where album_id = ?", target, source);
        jdbc.update("delete from media_album_person where album_id = ?", source);
        jdbc.update("delete from media_album where id = ?", source);
        recompute(jdbc, target);
        mediaIndexService.invalidateSnapshot();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved);
        out.put("source", source);
        out.put("target", target);
        out.put("aliasDir", src.get("dir"));
        out.put("aliasRoot", rootId);
        return out;
    }

    /**
     * 并入前预检：会搬多少张、其中多少张与目标册内容重复（同 content_hash）。
     *
     * <p>重复的图**不自动删** —— 一行照片 = 磁盘上一个文件，删行下次扫描就回来了；
     * 去重只能在「重复文件」页删物理文件。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @PostMapping("/merge/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body)
    {
        String source = text(body.get("source"));
        String target = text(body.get("target"));
        if (source == null || target == null)
        {
            throw new ServiceException("缺少 source / target");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer moving = jdbc.queryForObject(
                "select count(*) from media_photo where album_id = ?", Integer.class, source);
        Integer duplicates = jdbc.queryForObject(
                "select count(*) from media_photo s join media_photo t"
                        + " on t.content_hash = s.content_hash and t.album_id = ?"
                        + " where s.album_id = ? and s.content_hash is not null",
                Integer.class, target, source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("target", target);
        out.put("moving", moving == null ? 0 : moving.intValue());
        out.put("duplicates", duplicates == null ? 0 : duplicates.intValue());
        return out;
    }

    /**
     * 搬入前预检：目标册里已有几份同内容（P6.51）。
     *
     * <p>口径：**目标册**里已存在的同 {@code content_hash} 行，且不是本次要搬的那几条。
     * 命中就意味着「搬进去之后同一本册里出现两份相同的图」，UI 据此弹三选一
     * （跳过重复 / 同时存在 / 只保留一份）。{@code globalCopies} 是全库口径，只作信息展示。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @PostMapping("/photos/move/preview")
    public Map<String, Object> movePreview(@RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String target = text(body.get("target"));
        if (target == null)
        {
            throw new ServiceException("缺少 target");
        }
        if (album(jdbc, target) == null)
        {
            throw new ServiceException("目标册不存在：" + target);
        }
        List<Long> ids = photoIds(body);
        List<Map<String, Object>> rows = photos(jdbc, ids);
        List<Map<String, Object>> twins = twins(jdbc, ids, target);
        Set<String> twinHashes = new LinkedHashSet<>();
        for (Map<String, Object> t : twins)
        {
            twinHashes.add(String.valueOf(t.get("content_hash")));
        }
        List<Long> colliding = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Object h = r.get("content_hash");
            if (h != null && twinHashes.contains(String.valueOf(h)))
            {
                colliding.add(Long.valueOf(String.valueOf(r.get("id"))));
            }
        }
        Integer global = jdbc.queryForObject(
                "select count(*) from media_photo t where t.content_hash is not null and t.content_hash <> ''"
                        + " and t.id not in (" + csv(ids) + ")"
                        + " and t.content_hash in (select s.content_hash from media_photo s where s.id in ("
                        + csv(ids) + ") and s.content_hash is not null)", Integer.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", target);
        out.put("moving", ids.size());
        // duplicates = 选中的图里有几张在目标册已有同内容副本（skip 会跳过它们、replace 会顶掉旧的那份）
        out.put("duplicates", colliding.size());
        out.put("dupPhotoIds", colliding);
        // targetCopies = 目标册里会被 replace 清掉的旧行数
        out.put("targetCopies", twins.size());
        out.put("globalCopies", global == null ? 0 : global.intValue());
        return out;
    }

    /**
     * 按图改归属 —— 移到另一本已有册（P6.51）。
     *
     * <p>与「并入另一册」的分工：并入是整册对整册，并登记目录别名让扫描认这个目录；
     * 这里是**按图**策展，目录不动，所以靠 {@code media_photo.album_pinned = 1}
     * 让重扫不再按目录把归属拽回去。</p>
     *
     * <p>{@code dupPolicy} 只处理「目标册里已有同内容」这一种冲突：</p>
     * <ul>
     *   <li>{@code keep}（默认）同时存在：全部搬入，重复继续留在库里，之后在重复文件页处理；</li>
     *   <li>{@code skip} 跳过重复：目标册已有同内容的图不搬，返回 {@code skipped}；</li>
     *   <li>{@code replace} 只保留一份：把目标册里旧的那份连文件移入回收站并删行，新搬入的留下。</li>
     * </ul>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/photos/move")
    public Map<String, Object> movePhotos(@RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String target = text(body.get("target"));
        if (target == null)
        {
            throw new ServiceException("缺少 target");
        }
        if (album(jdbc, target) == null)
        {
            throw new ServiceException("目标册不存在：" + target);
        }
        String policy = text(body.get("dupPolicy"));
        if (policy == null)
        {
            policy = "keep";
        }
        if (!"keep".equals(policy) && !"skip".equals(policy) && !"replace".equals(policy))
        {
            throw new ServiceException("dupPolicy 只能是 keep / skip / replace");
        }
        List<Long> ids = photoIds(body);
        List<Map<String, Object>> rows = photos(jdbc, ids);
        boolean allHere = true;
        for (Map<String, Object> r : rows)
        {
            if (!target.equals(String.valueOf(r.get("album_id"))))
            {
                allHere = false;
                break;
            }
        }
        if (allHere)
        {
            throw new ServiceException("选中的图已经在这本册里");
        }

        List<Map<String, Object>> tw = twins(jdbc, ids, target);
        Set<String> twinHashes = new LinkedHashSet<>();
        for (Map<String, Object> t : tw)
        {
            twinHashes.add(String.valueOf(t.get("content_hash")));
        }

        Map<String, Object> out;
        if ("skip".equals(policy))
        {
            List<Map<String, Object>> keepRows = new ArrayList<>();
            for (Map<String, Object> r : rows)
            {
                Object h = r.get("content_hash");
                if (h == null || !twinHashes.contains(String.valueOf(h)))
                {
                    keepRows.add(r);
                }
            }
            int skipped = rows.size() - keepRows.size();
            if (keepRows.isEmpty())
            {
                // 用户选了「跳过重复」，全被跳过是预期结果，不是错误：返回 200 让前端提示，
                // 别抛 ServiceException（那会被错误处理链改写成 HTTP 500，用户只看到「系统接口500异常」）
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("moved", 0);
                none.put("skipped", skipped);
                none.put("target", target);
                none.put("from", new ArrayList<String>());
                none.put("dupPolicy", policy);
                return none;
            }
            out = relocate(jdbc, keepRows, target);
            out.put("skipped", skipped);
        }
        else
        {
            out = relocate(jdbc, rows, target);
            out.put("skipped", 0);
            if ("replace".equals(policy) && !tw.isEmpty())
            {
                List<Map<String, Object>> removed = new ArrayList<>();
                for (Map<String, Object> t : tw)
                {
                    removed.add(photoRemover.remove(((Number) t.get("id")).longValue()));
                }
                recompute(jdbc, target);
                out.put("replaced", removed.size());
                out.put("replacedRows", removed);
            }
            else
            {
                out.put("replaced", 0);
            }
        }
        out.put("dupPolicy", policy);
        // 核对用：没有管理端 token 时，这行日志是唯一能证明「接口到底搬了哪几张」的证据
        log.info("按图改归属 target={} policy={} 选中={} 搬移={} 跳过={} 替换={}",
                target, policy, ids.size(), out.get("moved"), out.get("skipped"), out.get("replaced"));
        return out;
    }

    /**
     * 册内人工排序（P6.57）：{@code POST /media/admin/albums/photos/reorder {album, photoIds, startSeq}}
     *
     * <p>把 {@code photoIds} 按给定顺序依次写 {@code startSeq, startSeq+1, …}，只动这几行、且必须属于该册。
     * {@code startSeq} 由前端传"当前页第一张的 seq"，所以翻页时也能只排当前页。
     * ({@code album_id, seq}) 从 P6.47 起只是普通索引，重排不会撞唯一键。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/photos/reorder")
    public Map<String, Object> reorderPhotos(@RequestBody Map<String, Object> body)
    {
        String album = text(body.get("album"));
        if (album == null)
        {
            throw new ServiceException("缺少 album");
        }
        List<Long> ids = photoIds(body);
        if (ids.size() > 500)
        {
            throw new ServiceException("一次最多重排 500 张");
        }
        int startSeq = 0;
        Object rawStart = body.get("startSeq");
        if (rawStart != null)
        {
            try
            {
                startSeq = Integer.parseInt(String.valueOf(rawStart).trim());
            }
            catch (NumberFormatException e)
            {
                throw new ServiceException("startSeq 必须是数字：" + rawStart);
            }
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++)
        {
            batch.add(new Object[] { Integer.valueOf(startSeq + i), ids.get(i).longValue(), album });
        }
        int[] affected = jdbc.batchUpdate(
                "update media_photo set seq = ? where id = ? and album_id = ?", batch);
        int updated = countAffected(affected);
        mediaIndexService.invalidateSnapshot();
        log.info("册内排序 album={} startSeq={} 计划={} 实际={}", album, startSeq, ids.size(), updated);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("album", album);
        out.put("startSeq", startSeq);
        return out;
    }

    /**
     * 整册打散为「未归类」（P6.55）：入站队列里的第三个决定。
     *
     * <p>把这本册的照片全部 {@code album_id = NULL + album_pinned = 1}，册变成 0 行；
     * 按 §四.9 的口径，下一次扫描会把它清掉——等于「这本建议我不接受，图先留着」。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/photos/detach-album")
    public Map<String, Object> detachAlbum(@RequestBody Map<String, Object> body)
    {
        String album = text(body.get("album"));
        if (album == null)
        {
            throw new ServiceException("缺少 album");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (album(jdbc, album) == null)
        {
            throw new ServiceException("册不存在：" + album);
        }
        int detached = jdbc.update(
                "update media_photo set album_id = null, album_pinned = 1 where album_id = ?", album);
        recompute(jdbc, album);
        mediaIndexService.invalidateSnapshot();
        log.info("整册打散为未归类 album={} 张数={}", album, detached);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detached", detached);
        out.put("album", album);
        return out;
    }

    /**
     * 采纳建议册（P6.54）：{@code POST /media/admin/albums/confirm {ids:["a170", ...]}}
     *
     * <p>扫描按目录建出来的册默认 @@confirmed = 0@@（建议册），站端看不到；
     * 这里把它们变成「已采纳」。册的内容（照片归属）扫描时就已经成立了，采纳只是一个开关——
     * 所以采纳后不需要任何搬运/重算，只需索引快照失效。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/confirm")
    public Map<String, Object> confirmAlbums(@RequestBody Map<String, Object> body)
    {
        Object raw = body.get("ids");
        if (!(raw instanceof List))
        {
            throw new ServiceException("缺少 ids 数组");
        }
        List<String> ids = new ArrayList<>();
        for (Object o : (List<?>) raw)
        {
            String id = text(o);
            if (id != null)
            {
                ids.add(id);
            }
        }
        if (ids.isEmpty())
        {
            throw new ServiceException("ids 不能为空");
        }
        if (ids.size() > 500)
        {
            throw new ServiceException("一次最多采纳 500 本");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            if (i > 0)
            {
                in.append(',');
            }
            in.append(quote(ids.get(i)));
        }
        int updated = jdbc.update("update media_album set confirmed = 1 where confirmed <> 1 and id in (" + in + ")");
        mediaIndexService.invalidateSnapshot();
        log.info("采纳建议册 ids={} 实际采纳={}", ids, updated);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", updated);
        out.put("ids", ids);
        return out;
    }

    /**
     * 移出册 → 未归类（P6.52）。
     *
     * <p>一张照片「最多属于一个系列（0..1）」，0 这一头就是这里：{@code album_id = NULL}。
     * 同时置 {@code album_pinned = 1}——空归属是人工决定，扫描不许按目录把它拽回去。
     * 站端所有图片查询都按 album 走，所以无册照片天然不会出现在站上。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/photos/detach")
    public Map<String, Object> detachPhotos(@RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Long> ids = photoIds(body);
        List<Map<String, Object>> rows = photos(jdbc, ids);
        Set<String> sources = new LinkedHashSet<>();
        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Object album = r.get("album_id");
            if (album != null)
            {
                sources.add(String.valueOf(album));
            }
            batch.add(new Object[] { r.get("id") });
        }
        int[] affected = jdbc.batchUpdate(
                "update media_photo set album_id = null, album_pinned = 1 where id = ?", batch);
        int detached = countAffected(affected);
        for (String s : sources)
        {
            recompute(jdbc, s);
        }
        mediaIndexService.invalidateSnapshot();
        log.info("移出册（未归类）选中={} 实际={} 来源册={}", ids.size(), detached, sources);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detached", detached);
        out.put("from", new ArrayList<String>(sources));
        return out;
    }

    /**
     * 未归类照片列表：{@code GET /media/admin/albums/photos/uncategorized?page=1&size=48}
     *
     * <p>缩略图只给内容寻址 URL（{@code /api/media/thumb/p/<hash>}）：无册的行没有
     * albumId/seq 可以回退，正好也逼着老库先把哈希补上。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/photos/uncategorized")
    public Map<String, Object> uncategorized(
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "48") int size,
            @RequestParam(value = "keyword", required = false) String keyword)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(200, size));
        String kw = text(keyword);
        String where = " where album_id is null" + (kw == null ? "" : " and rel_path like " + quote("%" + kw + "%"));
        Integer total = jdbc.queryForObject("select count(*) from media_photo" + where, Integer.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, rel_path as relPath, bytes, root_id as rootId, content_hash as contentHash,"
                        + " seq, hidden, coalesce(missing, 0) as missing"
                        + " from media_photo" + where + " order by id desc limit ? offset ?",
                s, (p - 1) * s);
        for (Map<String, Object> r : rows)
        {
            Object hash = r.get("contentHash");
            String h = hash == null ? null : String.valueOf(hash).trim();
            r.put("thumbUrl", h == null || h.isEmpty() ? null : "/api/media/thumb/p/" + h);
            r.put("previewUrl", h == null || h.isEmpty() ? null : "/api/media/preview/p/" + h);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total.intValue());
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return out;
    }

    /**
     * 按图拆出 —— 把选中的图挪进一本**新建**的册（P6.51）。
     *
     * <p>新册不是目录册：dir_key 为 NULL、source='manual'，所以扫描既不会按目录认领它，
     * 也不会把它当空壳清理。封面留空（搬进来的图缩略图仍在原册目录下）；
     * 审核状态走默认值 pending，要上站得先在审核页通过。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/photos/split")
    public Map<String, Object> splitPhotos(@RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String title = text(body.get("title"));
        if (title == null)
        {
            throw new ServiceException("缺少新册标题");
        }
        if (title.length() > 300)
        {
            throw new ServiceException("标题过长（上限 300 字）");
        }
        List<Map<String, Object>> rows = photos(jdbc, photoIds(body));
        String id = allocAlbumId(jdbc);
        jdbc.update("insert into media_album (id, person_id, title, dir, photo_count, bytes, cover_path,"
                + " note, sort, hidden, dir_key, identity_key, source)"
                + " values (?, null, ?, '', 0, 0, null, '', 0, 0, null, '', 'manual')", id, title);
        Map<String, Object> out;
        try
        {
            out = relocate(jdbc, rows, id);
        }
        catch (RuntimeException e)
        {
            // 搬移失败就把刚建的册撤掉，别留一本空壳册在列表里
            jdbc.update("delete from media_album where id = ?", id);
            throw e;
        }
        out.put("source", id);
        out.put("title", title);
        return out;
    }

    /**
     * 未归类照片按**目录**汇总：{@code GET /media/admin/albums/photos/uncategorized/dirs}
     *
     * <p>规则模式下扫描不再按目录建册，新目录进来的照片全都停在「未归类」。这里把它们的目录
     * 聚出来（带张数、是否已有规则），人一次点击就能"立规则 + 建册并入"（见 {@code /claim}）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/photos/uncategorized/dirs")
    public List<Map<String, Object>> uncategorizedDirs()
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select case when locate('/', rel_path) > 0"
                        + " then substring(rel_path, 1, length(rel_path) - locate('/', reverse(rel_path)))"
                        + " else '' end as dir,"
                        + " count(*) as photos, coalesce(sum(bytes), 0) as bytes,"
                        + " min(root_id) as rootId"
                        + " from media_photo where album_id is null group by dir order by photos desc limit 200");
        for (Map<String, Object> r : rows)
        {
            Object dir = r.get("dir");
            String d = dir == null ? "" : String.valueOf(dir);
            Integer has = jdbc.queryForObject("select count(*) from media_album_rule where dir_pattern = ?",
                    Integer.class, d);
            r.put("ruleCount", has == null ? Integer.valueOf(0) : has);
        }
        return rows;
    }

    /**
     * 一键：按目录**建册 + 建规则 + 把该目录未归类照片移进去**
     * {@code POST /media/admin/albums/photos/uncategorized/claim {dir, title?}}
     *
     * <p>这是规则模式下唯一"新增归属"的人手入口：册与规则同时落地，之后同目录的新文件
     * 在下次扫描时会被规则自动认领（扫描只认领，不建册）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/photos/uncategorized/claim")
    public Map<String, Object> claimDir(@RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Object rawDir = body == null ? null : body.get("dir");
        String dir = rawDir == null ? "" : String.valueOf(rawDir).trim();
        if (dir.endsWith("/"))
        {
            dir = dir.substring(0, dir.length() - 1);
        }
        String like = dir.isEmpty() ? "%" : dir.replace("\\", "/") + "/%";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, rel_path, bytes from media_photo where album_id is null and rel_path like ?"
                        + " order by rel_path asc", like);
        if (rows.isEmpty())
        {
            throw new ServiceException("这个目录下没有未归类照片了：[" + dir + "]");
        }
        Object rawTitle = body.get("title");
        String title = rawTitle == null ? "" : String.valueOf(rawTitle).trim();
        if (title.isEmpty())
        {
            int slash = dir.lastIndexOf('/');
            title = slash >= 0 ? dir.substring(slash + 1) : (dir.isEmpty() ? "未命名册" : dir);
        }
        if (title.length() > 300)
        {
            throw new ServiceException("标题过长（上限 300 字）");
        }
        String id = allocAlbumId(jdbc);
        jdbc.update("insert into media_album (id, person_id, title, dir, photo_count, bytes, cover_path,"
                + " note, sort, hidden, dir_key, identity_key, source, confirmed, review_state)"
                + " values (?, null, ?, ?, 0, 0, null, '', 0, 0, ?, '*', 'manual', 1, 'approved')",
                id, title, dir, dir);
        // 规则：同目录后续自动认领（不带前缀 = 同目录全部文件）
        try
        {
            jdbc.update("insert ignore into media_album_rule (album_id, dir_pattern, file_prefix, priority, note)"
                    + " values (?, ?, null, 100, '未归类一键建册')", id, dir);
        }
        catch (RuntimeException e)
        {
            // 规则表缺失不该让这次归册失败：先归上，规则留给运维补
            log.warn("建规则失败（album={} dir={}）：{}", id, dir, e.getMessage());
        }
        // 按 rel_path 顺序落 seq（P6.47 起 (album_id,seq) 只是普通索引，不会撞唯一键）
        for (int i = 0; i < rows.size(); i++)
        {
            jdbc.update("update media_photo set album_id = ?, seq = ? where id = ?",
                    id, Integer.valueOf(i), rows.get(i).get("id"));
        }
        jdbc.update("update media_album a set photo_count = (select count(*) from media_photo p where p.album_id = a.id),"
                + " bytes = (select coalesce(sum(p.bytes),0) from media_photo p where p.album_id = a.id)"
                + " where a.id = ?", id);
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("albumId", id);
        out.put("title", title);
        out.put("dir", dir);
        out.put("moved", Integer.valueOf(rows.size()));
        log.info("未归类一键建册：dir={} album={} title={} 移入 {} 张", dir, id, title, rows.size());
        return out;
    }

    /** 某本册当前挂了哪些「并入目录」 */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/{id}/dirs")
    public List<Map<String, Object>> dirs(@PathVariable("id") String id)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return jdbc.queryForList("select root_id as rootId, dir, created_at as createdAt"
                + " from media_album_dir where album_id = ? order by created_at asc", id);
    }

    /** 撤销某条并入（照片不回迁，只让下次扫描重新按目录建册） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @DeleteMapping("/dirs")
    public Map<String, Object> removeDir(@RequestBody Map<String, Object> body)
    {
        String dir = text(body.get("dir"));
        Object rootId = body.get("rootId");
        if (dir == null || rootId == null)
        {
            throw new ServiceException("缺少 dir / rootId");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int removed = jdbc.update("delete from media_album_dir where root_id = ? and dir = ?",
                Integer.valueOf(String.valueOf(rootId)), dir);
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", removed);
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static Map<String, Object> album(JdbcTemplate jdbc, String id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, title, dir from media_album where id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Integer dominantRoot(JdbcTemplate jdbc, String albumId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select root_id, count(*) n from media_photo where album_id = ? and root_id is not null"
                        + " group by root_id order by n desc limit 1", albumId);
        if (rows.isEmpty())
        {
            return null;
        }
        return Integer.valueOf(String.valueOf(rows.get(0).get("root_id")));
    }

    /** 册内下一个空序号：跳过已停泊的缺失行（scan-media.js 的 PARK_BASE = 1000000） */
    private static int nextSeq(JdbcTemplate jdbc, String albumId)
    {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(seq), -1) from media_photo where album_id = ? and seq < 1000000",
                Integer.class, albumId);
        return max == null ? 0 : max + 1;
    }

    /**
     * 把 rows 里的照片挂到 target，并标记 album_pinned = 1。
     *
     * <p>seq 一律追加到目标册末尾（按来源的 album_id, seq 顺序），不改磁盘上的任何文件。</p>
     */
    private Map<String, Object> relocate(JdbcTemplate jdbc, List<Map<String, Object>> rows, String target)
    {
        int base = nextSeq(jdbc, target);
        Set<String> sources = new LinkedHashSet<>();
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> r = rows.get(i);
            sources.add(String.valueOf(r.get("album_id")));
            batch.add(new Object[] { target, Integer.valueOf(base + i), r.get("id") });
        }
        // moved 取「实际更新了几行」而不是「打算更新几行」：批量写如果一行都没命中，
        // 返回 rows.size() 会让界面报成功、库里却什么都没变（P6.51 踩过一次）
        int[] affected = jdbc.batchUpdate(
                "update media_photo set album_id = ?, seq = ?, album_pinned = 1 where id = ?", batch);
        int moved = countAffected(affected);
        if (moved != rows.size())
        {
            log.warn("按图改归属：批量更新命中的行数 {} != 计划 {}（ids={}）", moved, rows.size(),
                    rows.stream().map(r -> String.valueOf(r.get("id"))).reduce((a, b) -> a + "," + b).orElse(""));
        }
        sources.remove(target);
        for (String s : sources)
        {
            recompute(jdbc, s);
        }
        // P6.56：目标册已经「采纳 + 通过」时，被移入的照片要单独过一遍审核——
        // 审核触发器只覆盖 INSERT，UPDATE album_id 不触发，不补这一步就是「未审先上线」。
        int pending = 0;
        if (isLiveAlbum(jdbc, target))
        {
            pending = jdbc.update("update media_photo set review_state = 'pending'"
                    + " where id in (" + csvOfRows(rows) + ")"
                    + " and (review_state is null or review_state = 'approved')");
        }
        recompute(jdbc, target);
        mediaIndexService.invalidateSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", moved);
        out.put("pendingReview", pending);
        out.put("target", target);
        out.put("from", new ArrayList<String>(sources));
        return out;
    }

    /** 新册 id：沿用扫描器的 aNNN 格式，取现有最大编号 +1（不回收空号，避免和旧日志/别名串味） */
    private static String allocAlbumId(JdbcTemplate jdbc)
    {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(cast(substring(id, 2) as unsigned)), 0) from media_album"
                        + " where id regexp '^a[0-9]+$'", Integer.class);
        return "a" + String.format("%03d", (max == null ? 0 : max.intValue()) + 1);
    }

    /** 按 id 取照片行（顺序按来源 album_id, seq，保证搬过去后相对顺序不变） */
    private static List<Map<String, Object>> photos(JdbcTemplate jdbc, List<Long> ids)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(ids.get(i).longValue());
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, album_id, seq, content_hash from media_photo where id in (" + sb
                        + ") order by album_id, seq");
        if (rows.size() != ids.size())
        {
            throw new ServiceException("有 " + (ids.size() - rows.size()) + " 张照片不存在（可能刚被删除）");
        }
        return rows;
    }

    /** 目标册里「已有同内容、且不是本次要搬的那几条」的行：skip 跳过对应待搬图，replace 清掉它们 */
    private static List<Map<String, Object>> twins(JdbcTemplate jdbc, List<Long> ids, String target)
    {
        String list = csv(ids);
        return jdbc.queryForList(
                "select t.id as id, t.content_hash as content_hash from media_photo t"
                        + " where t.album_id = ? and t.content_hash is not null and t.content_hash <> ''"
                        + " and t.id not in (" + list + ")"
                        + " and t.content_hash in (select s.content_hash from media_photo s"
                        + " where s.id in (" + list + ") and s.content_hash is not null)"
                        + " order by t.seq", target);
    }

    /** 这本册是不是「已上站」：采纳 + 审核通过（只有这种册需要给移入的照片补一道审核） */
    private static boolean isLiveAlbum(JdbcTemplate jdbc, String albumId)
    {
        Integer n = jdbc.queryForObject(
                "select count(*) from media_album where id = ? and confirmed = 1 and review_state = 'approved'",
                Integer.class, albumId);
        return n != null && n.intValue() > 0;
    }

    /** rows 里所有 id 拼成 SQL 的 IN 列表 */
    private static String csvOfRows(List<Map<String, Object>> rows)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(String.valueOf(rows.get(i).get("id")));
        }
        return sb.toString();
    }

    /** 批量更新实际命中了几行（SUCCESS_NO_INFO 也算命中） */
    private static int countAffected(int[] affected)
    {
        int n = 0;
        for (int v : affected)
        {
            if (v > 0 || v == java.sql.Statement.SUCCESS_NO_INFO)
            {
                n++;
            }
        }
        return n;
    }

    private static String quote(String value)
    {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String csv(List<Long> ids)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(ids.get(i).longValue());
        }
        return sb.toString();
    }

    /** body.photoIds 解析：数组、非空、上限 2000（一次搬太多会把单条 SQL 撑爆） */
    @SuppressWarnings("unchecked")
    private static List<Long> photoIds(Map<String, Object> body)
    {
        Object raw = body.get("photoIds");
        if (!(raw instanceof List))
        {
            throw new ServiceException("缺少 photoIds 数组");
        }
        List<Long> ids = new ArrayList<>();
        for (Object o : (List<Object>) raw)
        {
            if (o == null)
            {
                continue;
            }
            try
            {
                ids.add(Long.valueOf(String.valueOf(o).trim()));
            }
            catch (NumberFormatException e)
            {
                throw new ServiceException("photoIds 里有非数字：" + o);
            }
        }
        if (ids.isEmpty())
        {
            throw new ServiceException("photoIds 不能为空");
        }
        if (ids.size() > 2000)
        {
            throw new ServiceException("一次最多搬 2000 张");
        }
        return ids;
    }

    private static void recompute(JdbcTemplate jdbc, String albumId)
    {
        jdbc.update("update media_album a set photo_count = (select count(*) from media_photo p where p.album_id = a.id),"
                + " bytes = (select coalesce(sum(p.bytes), 0) from media_photo p where p.album_id = a.id)"
                + " where a.id = ?", albumId);
        // 封面沿用原值（新并入的图不一定生成过缩略图，重建交给扫描/维护任务）
    }

    private static String text(Object value)
    {
        String v = value == null ? null : String.valueOf(value).trim();
        return v == null || v.isEmpty() ? null : v;
    }
}


