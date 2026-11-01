package com.qtools.media.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.enums.BusinessType;
import com.qtools.common.exception.ServiceException;

/**
 * 相册归属规则（P6.90）—— {@code media_album_rule} 的维护入口
 *
 * <p>P6.88 之后扫描器<b>不再按目录建册</b>：一张照片归哪本册，由本表的规则决定，
 * 命中不了就是「未归类」。规则原本只能通过「未归类 → 按目录建册并入」隐式产生，
 * 这个控制器把它变成可看、可改、可停用、可删除的东西。</p>
 *
 * <p>规则只影响**扫描时的归属判定**，不参与任何查询/可见性判定，所以增删改都只需落库，
 * 不需要失效索引快照。</p>
 */
@RestController
@RequestMapping("/media/admin/album-rules")
public class MediaAlbumRuleController
{
    private static final Logger log = LoggerFactory.getLogger(MediaAlbumRuleController.class);

    @Autowired
    private DataSource dataSource;

    /**
     * 规则列表：{@code GET /media/admin/album-rules?keyword=}
     *
     * <p>返回 {@code [{id, albumId, albumTitle, albumPhotoCount, dirPattern, filePrefix, priority,
     * enabled, note, createdAt}]}，按 优先级 → 目录 排序；keyword 同时匹配目录与册名。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(value = "keyword", required = false) String keyword)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StringBuilder sql = new StringBuilder(
                "select r.id, r.album_id as albumId, a.title as albumTitle,"
                        + " coalesce(a.photo_count, 0) as albumPhotoCount,"
                        + " r.dir_pattern as dirPattern, r.file_prefix as filePrefix,"
                        + " r.priority, r.enabled, r.note, r.created_at as createdAt"
                        + " from media_album_rule r left join media_album a on a.id = r.album_id");
        String kw = keyword == null ? "" : keyword.trim();
        List<Object> args = new ArrayList<>();
        if (!kw.isEmpty())
        {
            sql.append(" where r.dir_pattern like ? or a.title like ? or r.album_id = ?");
            args.add("%" + kw + "%");
            args.add("%" + kw + "%");
            args.add(kw);
        }
        sql.append(" order by r.priority asc, r.dir_pattern asc, r.file_prefix asc limit 500");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows)
        {
            Object e = r.get("enabled");
            r.put("enabled", Boolean.valueOf(e != null && Integer.parseInt(String.valueOf(e).trim()) == 1));
        }
        return rows;
    }

    /**
     * 新建规则：{@code POST /media/admin/album-rules {albumId, dirPattern, filePrefix?, priority?}}
     *
     * <p>给"我已经有这本册了，只补一条规则"的场景（另一条路是「未归类 → 按目录建册并入」，
     * 那次会连册一起建）。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-归属规则", businessType = BusinessType.INSERT)
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String albumId = str(body, "albumId");
        if (albumId == null)
        {
            throw new ServiceException("缺少 albumId");
        }
        Integer exists = jdbc.queryForObject("select count(*) from media_album where id = ?", Integer.class, albumId);
        if (exists == null || exists.intValue() == 0)
        {
            throw new ServiceException("相册不存在：" + albumId);
        }
        String dir = normalizeDir(str(body, "dirPattern"));
        String prefix = str(body, "filePrefix");
        int priority = intOf(body, "priority", prefix == null ? 100 : 10);
        jdbc.update("insert into media_album_rule (album_id, dir_pattern, file_prefix, priority, enabled, note)"
                + " values (?, ?, ?, ?, 1, ?) on duplicate key update priority = values(priority), enabled = 1",
                albumId, dir, prefix, Integer.valueOf(priority), str(body, "note"));
        Map<String, Object> row = jdbc.queryForMap(
                "select id from media_album_rule where album_id = ? and dir_pattern = ? and file_prefix <=> ?",
                albumId, dir, prefix);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("albumId", albumId);
        out.put("dirPattern", dir);
        out.put("filePrefix", prefix);
        out.put("priority", Integer.valueOf(priority));
        log.info("归属规则新建：album={} dir={} prefix={} priority={}", albumId, dir, prefix, priority);
        return out;
    }

    /** 启停 / 调优先级：{@code PUT /media/admin/album-rules/{id} {enabled?, priority?, filePrefix?}} */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-归属规则", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") int id, @RequestBody Map<String, Object> body)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> found = jdbc.queryForList(
                "select id from media_album_rule where id = ?", Integer.valueOf(id));
        if (found.isEmpty())
        {
            throw new ServiceException("规则不存在：" + id);
        }
        if (body != null && body.containsKey("enabled"))
        {
            Object e = body.get("enabled");
            boolean on = e instanceof Boolean ? ((Boolean) e).booleanValue() : "1".equals(String.valueOf(e))
                    || "true".equalsIgnoreCase(String.valueOf(e));
            jdbc.update("update media_album_rule set enabled = ? where id = ?", Integer.valueOf(on ? 1 : 0),
                    Integer.valueOf(id));
        }
        if (body != null && body.containsKey("priority"))
        {
            jdbc.update("update media_album_rule set priority = ? where id = ?",
                    Integer.valueOf(intOf(body, "priority", 100)), Integer.valueOf(id));
        }
        if (body != null && body.containsKey("filePrefix"))
        {
            jdbc.update("update media_album_rule set file_prefix = ? where id = ?", str(body, "filePrefix"),
                    Integer.valueOf(id));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", Integer.valueOf(id));
        return out;
    }

    /** 删除规则：{@code DELETE /media/admin/album-rules/{id}}（只影响后续扫描的自动归属，不动已有照片） */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @Log(title = "媒体库-归属规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") int id)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int n = jdbc.update("delete from media_album_rule where id = ?", Integer.valueOf(id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", Integer.valueOf(n));
        out.put("id", Integer.valueOf(id));
        log.info("归属规则删除：id={} 影响 {} 行", id, n);
        return out;
    }

    /** 规则命中的目录候选（给新建时做提示）：来自未归类照片的实际目录 */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/dirs")
    public List<Map<String, Object>> dirs()
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return jdbc.queryForList(
                "select case when locate('/', rel_path) > 0"
                        + " then substring(rel_path, 1, length(rel_path) - locate('/', reverse(rel_path)))"
                        + " else '' end as dir, count(*) as photos"
                        + " from media_photo where album_id is null group by dir order by photos desc limit 200");
    }

    private static String normalizeDir(String raw)
    {
        String d = raw == null ? "" : raw.trim().replace("\\", "/");
        while (d.startsWith("/"))
        {
            d = d.substring(1);
        }
        while (d.endsWith("/"))
        {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    private static String str(Map<String, Object> body, String key)
    {
        if (body == null || body.get(key) == null)
        {
            return null;
        }
        String v = String.valueOf(body.get(key)).trim();
        return v.isEmpty() ? null : v;
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
}
