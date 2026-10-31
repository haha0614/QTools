package com.qtools.media.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.exception.ServiceException;
import com.qtools.media.support.MediaPhotoRemover;

/**
 * 同内容多份（主行维度）的重复文件接口（P6.51）。
 *
 * <p><b>与 {@link MediaDuplicateController}（副本路径）的分工</b>：那一组接口管理的是别名表
 * {@code media_photo_alias}——「有路径、没有主行」的第二份文件。本组接口管的是另一件事：
 * <b>同一份内容在库里存在多条主行</b>。按原子归属的口径（一张照片只属于一个册），
 * 多条主行同 {@code content_hash} 就是同一个文件的多份，无论它们在哪本册里。</p>
 *
 * <p>为什么只支持照片：{@code media_video.content_hash} 上有唯一键 {@code uk_media_video_hash}
 * （见 {@code doc/media_dup_alias.sql} 的说明），视频写不进第二条同内容的主行；
 * 视频的重复只存在于别名表，走另一组接口。</p>
 *
 * <p><b>删除语义</b>：一律「文件移入回收站 + 删行 + 重算归属册」，由
 * {@link MediaPhotoRemover} 实现。不提供「只删行」开关——文件还在媒体根里时删行，
 * 下一轮扫描会原地复活，那是假删除。</p>
 */
@RestController
@RequestMapping("/media/admin/duplicates/main")
public class MediaDuplicateMainController
{
    /** 单页上限：一个组最多可能很多份，但页面一次只展一页分组 */
    private static final int MAX_SIZE = 50;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MediaPhotoRemover photoRemover;

    /**
     * 同内容分组：{@code GET /media/admin/duplicates/main/groups?kind=photo&page=1&size=10}
     *
     * <p>返回 {@code {total, page, size, rows:[{contentHash, copies, totalBytes, members:[...]}]}}，
     * 成员带 {@code albumId / albumTitle / seq / relPath / size}，让「保留哪一份」是一个
     * 人能判断的问题，而不是只看两个哈希。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:list')")
    @GetMapping("/groups")
    public Map<String, Object> groups(@RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "10") int size)
    {
        String k = text(kind) == null ? "photo" : text(kind);
        if (!"photo".equals(k))
        {
            throw new ServiceException("同内容多份目前只支持照片（kind=photo）："
                    + "media_video.content_hash 有唯一键，视频不会出现两条同内容主行");
        }
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(MAX_SIZE, size));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer total = jdbc.queryForObject(
                "select count(*) from (select content_hash from media_photo"
                        + " where content_hash is not null and content_hash <> ''"
                        + " group by content_hash having count(*) > 1) g", Integer.class);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", k);
        out.put("page", p);
        out.put("size", s);
        out.put("total", total == null ? 0 : total.intValue());
        if (total == null || total.intValue() == 0)
        {
            out.put("rows", new ArrayList<Map<String, Object>>());
            return out;
        }

        List<Map<String, Object>> head = jdbc.queryForList(
                "select content_hash as contentHash, count(*) as copies, coalesce(sum(bytes), 0) as totalBytes"
                        + " from media_photo where content_hash is not null and content_hash <> ''"
                        + " group by content_hash having count(*) > 1"
                        + " order by copies desc, content_hash asc limit ? offset ?",
                s, (p - 1) * s);
        if (head.isEmpty())
        {
            out.put("rows", new ArrayList<Map<String, Object>>());
            return out;
        }

        // 一页一次性取回所有成员，避免 N+1
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < head.size(); i++)
        {
            if (i > 0)
            {
                in.append(',');
            }
            in.append(quote(String.valueOf(head.get(i).get("contentHash"))));
        }
        List<Map<String, Object>> memberRows = jdbc.queryForList(
                "select p.id as id, p.content_hash as contentHash, p.album_id as albumId,"
                        + " a.title as albumTitle, p.seq as seq, p.rel_path as relPath, p.bytes as size,"
                        + " p.root_id as rootId, p.hidden as hidden, p.album_pinned as pinned,"
                        + " coalesce(p.missing, 0) as missing"
                        + " from media_photo p left join media_album a on a.id = p.album_id"
                        + " where p.content_hash in (" + in + ")"
                        + " order by p.content_hash, p.album_id, p.seq");

        Map<String, List<Map<String, Object>>> byHash = new LinkedHashMap<>();
        for (Map<String, Object> m : memberRows)
        {
            String hash = String.valueOf(m.get("contentHash"));
            List<Map<String, Object>> list = byHash.get(hash);
            if (list == null)
            {
                list = new ArrayList<>();
                byHash.put(hash, list);
            }
            list.add(m);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> g : head)
        {
            String hash = String.valueOf(g.get("contentHash"));
            List<Map<String, Object>> members = byHash.get(hash);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contentHash", hash);
            row.put("copies", g.get("copies"));
            row.put("totalBytes", g.get("totalBytes"));
            row.put("members", members == null ? new ArrayList<Map<String, Object>>() : members);
            rows.add(row);
        }
        out.put("rows", rows);
        return out;
    }

    /**
     * 清理组内某一份（只这一份）：{@code POST /media/admin/duplicates/main/remove {memberId}}
     *
     * <p>文件移入回收站 + 删行 + 重算所属册。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/remove")
    public Map<String, Object> remove(@RequestBody Map<String, Object> body)
    {
        Long memberId = longValue(body.get("memberId"));
        if (memberId == null)
        {
            throw new ServiceException("缺少 memberId");
        }
        return photoRemover.remove(memberId.longValue());
    }

    /**
     * 保留其中一份、其余全部清理：{@code POST /media/admin/duplicates/main/keep {contentHash, keepMemberId}}
     *
     * <p>逐个成员调用同一个删除动作（文件进回收站），单条失败不影响其它条，
     * 结果里分别给出 {@code removed} 与 {@code failed}。</p>
     */
    @PreAuthorize("@ss.hasPermi('media:admin:edit')")
    @PostMapping("/keep")
    public Map<String, Object> keep(@RequestBody Map<String, Object> body)
    {
        String hash = text(body.get("contentHash"));
        Long keepId = longValue(body.get("keepMemberId"));
        if (hash == null || keepId == null)
        {
            throw new ServiceException("缺少 contentHash / keepMemberId");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> members = jdbc.queryForList(
                "select id from media_photo where content_hash = ? order by album_id, seq", hash);
        if (members.isEmpty())
        {
            throw new ServiceException("这个内容哈希已经没有照片行：" + hash);
        }
        boolean keepExists = false;
        for (Map<String, Object> m : members)
        {
            if (keepId.longValue() == ((Number) m.get("id")).longValue())
            {
                keepExists = true;
                break;
            }
        }
        if (!keepExists)
        {
            throw new ServiceException("保留的那一份不属于内容哈希 " + hash + "（可能已被清理）");
        }
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Map<String, Object> m : members)
        {
            long id = ((Number) m.get("id")).longValue();
            if (id == keepId.longValue())
            {
                continue;
            }
            try
            {
                removed.add(photoRemover.remove(id));
            }
            catch (RuntimeException e)
            {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("message", e.getMessage());
                failed.add(f);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contentHash", hash);
        out.put("keptId", keepId);
        out.put("removed", removed);
        out.put("failed", failed);
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static String text(Object value)
    {
        String v = value == null ? null : String.valueOf(value).trim();
        return v == null || v.isEmpty() ? null : v;
    }

    private static Long longValue(Object value)
    {
        String v = text(value);
        if (v == null)
        {
            return null;
        }
        try
        {
            return Long.valueOf(v);
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("不是合法数字：" + v);
        }
    }

    private static String quote(String value)
    {
        return "'" + value.replace("'", "''") + "'";
    }
}
