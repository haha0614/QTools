package com.qtools.media.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.common.annotation.Anonymous;

/**
 * 作品（一个源站帖子 = 一个作品，含 N 段视频）的站端只读接口。
 *
 * <p>数据由 qtools-tool 的 WebAutoImportService 在「收藏 / 保存到视频站」时写入
 * （media_work + media_work_segment），这里只读。段地址用 /api/media/file 的 Range 实现，
 * 视频只保留媒体库那一份（HANDOFF §24）。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/media")
public class MediaWorkController
{
    private static final Logger log = LoggerFactory.getLogger(MediaWorkController.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;

    /** 作品列表：{@code GET /api/media/works?keyword=&limit=60&offset=0} */
    @GetMapping("/works")
    public Map<String, Object> works(@RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false, defaultValue = "60") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset)
    {
        int safeLimit = limit < 1 ? 60 : Math.min(limit, 200);
        int safeOffset = Math.max(offset, 0);
        String kw = keyword == null ? "" : keyword.trim();
        String where = kw.isEmpty() ? "" : " where w.title like ?";
        List<Object> args = new ArrayList<>();
        if (!kw.isEmpty())
        {
            args.add("%" + kw + "%");
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Integer.valueOf(safeLimit));
        pageArgs.add(Integer.valueOf(safeOffset));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select w.id, w.post_id, w.title, w.cover_path, w.seg_count, w.state,"
                        // 时长按段实时求和：入库时扫描还没跑完，dur 全是 NULL，写死 0 会一直显示 00:00
                        + " (select ifnull(sum(v.dur), 0) from media_work_segment s join media_video v on v.id = s.video_id"
                        + "   where s.work_id = w.id) as total_dur,"
                        + " (select s.video_id from media_work_segment s where s.work_id = w.id order by s.seq limit 1) as first_video_id,"
                        + " (select v.poster_path from media_work_segment s join media_video v on v.id = s.video_id"
                        + "   where s.work_id = w.id order by s.seq limit 1) as first_poster"
                        + " from media_work w" + where + " order by w.id desc limit ? offset ?",
                pageArgs.toArray());
        Integer total = jdbc.queryForObject("select count(*) from media_work w" + where, Integer.class, args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", r.get("id"));
            one.put("postId", r.get("post_id"));
            one.put("title", r.get("title"));
            one.put("coverPath", r.get("cover_path"));
            one.put("segCount", r.get("seg_count"));
            one.put("totalDur", r.get("total_dur"));
            one.put("state", r.get("state"));
            one.put("firstVideoId", r.get("first_video_id"));
            one.put("poster", r.get("first_poster"));
            out.add(one);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", total == null ? Integer.valueOf(0) : total);
        res.put("rows", out);
        return res;
    }

    /**
     * 作品详情：{@code GET /api/media/work/12}
     *
     * <p>返回标题、封面（源站封面）、正文图文 blocks、标签、逐段播放地址。
     * 段地址是 {@code /api/media/file?p=<rel_path>&r=<root_id>}，支持 Range 206。</p>
     */
    @GetMapping("/work/{id}")
    public Map<String, Object> work(@PathVariable("id") Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, post_id, source_id, title, cover_path, synopsis_blocks, seg_count, state,"
                        + " (select ifnull(sum(v.dur), 0) from media_work_segment s join media_video v on v.id = s.video_id"
                        + "   where s.work_id = media_work.id) as total_dur"
                        + " from media_work where id=?",
                id);
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty())
        {
            out.put("error", "作品不存在");
            return out;
        }
        Map<String, Object> w = rows.get(0);
        out.put("id", w.get("id"));
        out.put("postId", w.get("post_id"));
        out.put("sourceId", w.get("source_id"));
        out.put("title", w.get("title"));
        out.put("coverPath", w.get("cover_path"));
        out.put("segCount", w.get("seg_count"));
        out.put("totalDur", w.get("total_dur"));
        out.put("state", w.get("state"));
        out.put("blocks", parseBlocks(w.get("synopsis_blocks")));
        List<Map<String, Object>> segs = jdbc.queryForList(
                "select s.seq, s.video_id, v.rel_path, ifnull(v.root_id, 1) as root_id, ifnull(v.dur, 0) as dur,"
                        + " v.w, v.h, v.poster_path from media_work_segment s join media_video v on v.id = s.video_id"
                        + " where s.work_id=? order by s.seq",
                id);
        List<Map<String, Object>> segments = new ArrayList<>();
        List<Object> videoIds = new ArrayList<>();
        for (Map<String, Object> s : segs)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", s.get("seq"));
            one.put("videoId", s.get("video_id"));
            one.put("relPath", s.get("rel_path"));
            one.put("dur", s.get("dur"));
            one.put("w", s.get("w"));
            one.put("h", s.get("h"));
            one.put("poster", s.get("poster_path"));
            one.put("streamUrl", "/api/media/file?p="
                    + URLEncoder.encode(String.valueOf(s.get("rel_path")), StandardCharsets.UTF_8) + "&r=" + s.get("root_id"));
            segments.add(one);
            videoIds.add(s.get("video_id"));
        }
        out.put("segments", segments);
        out.put("tags", segments.isEmpty() ? List.of() : tagsOf(videoIds));
        return out;
    }

    /** 作品标签：取所有段的 media_video_tag 去重（导入时 applyTags 已按 segmentLike 给同帖全部段写同一套） */
    private List<Map<String, Object>> tagsOf(List<Object> videoIds)
    {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < videoIds.size(); i += 1)
        {
            in.append(i == 0 ? "?" : ",?");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select facet, name from media_video_tag where video_id in (" + in + ") group by facet, name order by facet, name",
                videoIds.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("facet", r.get("facet"));
            one.put("name", r.get("name"));
            out.add(one);
        }
        return out;
    }

    /** 正文存的是 blocks JSON 数组；解析失败就退回一段纯文本，别让详情页整页 500 */
    private Object parseBlocks(Object raw)
    {
        if (raw == null)
        {
            return List.of();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty())
        {
            return List.of();
        }
        try
        {
            return JSON.readValue(s, List.class);
        }
        catch (Exception e)
        {
            log.debug("作品正文不是合法 blocks JSON，按纯文本处理: {}", e.toString());
            return List.of(Map.of("type", "text", "text", s));
        }
    }
}
