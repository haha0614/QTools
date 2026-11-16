package com.qtools.tool.web.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.tool.mapper.ToolWebPostMapper;

/**
 * 「内容聚合」工具的后端只读接口（P6.110）
 *
 * <p>用途：把外部站点的条目索引到本站（{@code tool_web_post}），由我们自己的页面承载 ——
 * 去广告、按我们的分类/标签/搜索组织。内容归属仍是源站，本接口只回元数据。</p>
 *
 * <p>为什么不回播放地址：源站是签名 HLS（{@code auth_key} 带时效），存下来第二天就失效；
 * 播放由后续的代理接口在请求时现取（见 doc/web-mirror 的 sidecar 方案）。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/web")
public class WebPostController
{
    private static final Logger log = LoggerFactory.getLogger(WebPostController.class);

    /** 单页上限：一次回 200 条已经够瀑布流首屏，再大只是白传 */
    private static final int SIZE_MAX = 200;

    @Autowired
    private ToolWebPostMapper toolWebPostMapper;

    @Autowired
    private com.qtools.tool.web.support.WebOwnerResolver webOwnerResolver;

    /**
     * 条目分页：{@code GET /api/tool/web/posts?source=mrds&page=1&size=24&q=&category=&tag=}
     *
     * @return {@code {rows:[{id,title,category,tags[],cover,publishedAt,url,hasVideo}], total, page, size}}
     */
    @GetMapping("/posts")
    public Map<String, Object> posts(@RequestParam(value = "source", required = false, defaultValue = "mrds") String source,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "24") int size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader,
            @RequestParam(value = "dateFrom", required = false) String dateFrom,
            @RequestParam(value = "dateTo", required = false) String dateTo,
            @RequestParam(value = "featured", required = false) String featured,
            @RequestParam(value = "videoType", required = false) String videoType,
            @RequestParam(value = "minVideos", required = false) Integer minVideos)
    {
        int safePage = page < 1 ? 1 : page;
        int safeSize = size < 1 ? 24 : Math.min(size, SIZE_MAX);
        String cleanQ = q == null ? null : q.trim();
        // 搜索拆词：长度 ≥2 的词走 ngram 全文（多词 AND），单字词走 LIKE；单关键词时全文与标题/标签是「或」
        List<String> tokens = new ArrayList<>();
        if (cleanQ != null)
        {
            for (String piece : cleanQ.split("\\s+"))
            {
                String t = piece.replaceAll("[+\\-<>()~*\"@]", "").trim();
                if (!t.isEmpty() && !tokens.contains(t))
                {
                    tokens.add(t);
                }
            }
        }
        List<String> ftTokens = new ArrayList<>();
        List<String> likeTokens = new ArrayList<>();
        for (String t : tokens)
        {
            if (t.length() >= 2)
            {
                ftTokens.add(t);
            }
            else if (likeTokens.size() < 3)
            {
                likeTokens.add(t);
            }
        }
        StringBuilder ft = new StringBuilder();
        for (String t : ftTokens)
        {
            if (ft.length() > 0)
            {
                ft.append(' ');
            }
            ft.append('+').append(t);
        }
        String ftQuery = ft.length() == 0 ? null : ft.toString();
        Boolean ftOr = tokens.size() <= 1 ? Boolean.TRUE : Boolean.FALSE;
        String qLike = tokens.size() <= 1 ? cleanQ : null;
        String cleanCat = category == null ? null : category.trim();
        String cleanTag = tag == null ? null : tag.trim();
        List<String> cleanTags = new ArrayList<>();
        if (tags != null)
        {
            for (String piece : tags.split(","))
            {
                String t = piece.trim();
                if (!t.isEmpty() && cleanTags.size() < 20 && !cleanTags.contains(t))
                {
                    cleanTags.add(t);
                }
            }
        }
        // 标签黑名单按归属键服务端过滤：列表与 count 同时生效，分页数才准
        String owner = webOwnerResolver.resolve(authorization, ownerHeader, null);
        List<String> blockTags = owner == null ? List.of() : toolWebPostMapper.selectBlockedTags(owner);
        String cleanFrom = blankToNull(dateFrom);
        String cleanTo = blankToNull(dateTo);
        String cleanFeatured = blankToNull(featured);
        Map<String, Object> out = new LinkedHashMap<>();
        String cleanVideoType = blankToNull(videoType);
        List<Map<String, Object>> rows = toolWebPostMapper.selectPage(source, qLike, ftQuery, ftOr, likeTokens,
                cleanCat, cleanTag, cleanTags, blockTags, cleanFrom, cleanTo, cleanFeatured,
                cleanVideoType, minVideos, (safePage - 1) * safeSize, safeSize);
        for (Map<String, Object> row : rows)
        {
            row.put("tags", splitTags(row.get("tags")));
        }
        out.put("rows", rows);
        out.put("total", toolWebPostMapper.countPage(source, qLike, ftQuery, ftOr, likeTokens, cleanCat, cleanTag,
                cleanTags, blockTags, cleanFrom, cleanTo, cleanFeatured, cleanVideoType, minVideos));
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    /** 详情：{@code GET /api/tool/web/post/{id}}（id = 本站主键，不是源站 id） */
    @GetMapping("/post/{id}")
    public Map<String, Object> post(@PathVariable("id") Long id)
    {
        Map<String, Object> row = toolWebPostMapper.selectOne(id);
        if (row == null)
        {
            return Map.of("id", id, "error", "条目不存在");
        }
        row.put("tags", splitTags(row.get("tags")));
        // content 存的是 JSON 字符串：直接回给前端让 Vant/Vue 自己解析，避免后端再序列化一次
        row.put("blocks", parseBlocks(row.get("content")));
        row.remove("content");
        // 多视频：把 video_urls（JSON 数组）展开成列表给前端；老数据只有 video_url 时退回单元素
        List<String> videos = new ArrayList<>();
        Object rawUrls = row.remove("videoUrls");
        if (rawUrls != null)
        {
            try
            {
                videos = new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(rawUrls),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>()
                        {
                        });
            }
            catch (Exception ignored)
            {
                videos = new ArrayList<>();
            }
        }
        if (videos.isEmpty())
        {
            Object single = row.get("videoUrl");
            if (single != null && !String.valueOf(single).isEmpty())
            {
                videos.add(String.valueOf(single));
            }
        }
        row.put("videos", videos);
        return row;
    }

    /**
     * 筛选面板数据：{@code GET /api/tool/web/facets?source=mrds}
     *
     * @return {@code {categories:[{name,count}], tags:[{name,count}]}}（tags 按出现次数取前 40）
     */
    @GetMapping("/facets")
    public Map<String, Object> facets(@RequestParam(value = "source", required = false, defaultValue = "mrds") String source,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", toolWebPostMapper.selectCategoryCounts(source));
        String owner = webOwnerResolver.resolve(authorization, ownerHeader, null);
        List<String> blockedTags = owner == null ? List.of() : toolWebPostMapper.selectBlockedTags(owner);
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (String raw : toolWebPostMapper.selectAllTags(source))
        {
            for (String tag : splitTags(raw))
            {
                if (blockedTags.contains(tag))
                {
                    continue;
                }
                counter.merge(tag, Integer.valueOf(1), Integer::sum);
            }
        }
        List<Map<String, Object>> tags = new ArrayList<>();
        counter.entrySet().stream()
                .sorted((a, b) -> b.getValue().intValue() - a.getValue().intValue())
                .limit(40)
                .forEach(e -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("name", e.getKey());
                    node.put("count", e.getValue());
                    tags.add(node);
                });
        out.put("tags", tags);
        // 日期选择器用：可用日期清单（没有内容的日期置灰）+ 已补进度
        out.put("dates", toolWebPostMapper.selectDateCounts(source));
        out.put("blockedTags", blockedTags);
        out.put("dated", toolWebPostMapper.countDated(source));
        out.put("total", toolWebPostMapper.countAll(source));
        log.debug("内容聚合 facets source={} categories={} tags={}", source,
                ((List<?>) out.get("categories")).size(), tags.size());
        return out;
    }

    /** 空串当没传（前端清空日期框后会传 ""） */
    private static String blankToNull(String s)
    {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /**
     * 定位某条在列表里排第几：{@code GET /api/tool/web/locate?postId=123&category=&tags=…}
     *
     * <p>「回到上次浏览位置」用：带上当前筛选条件算出偏移量，前端直接取对应那一页，
     * 不需要一页页往下翻（翻到第 300 条要发 12 个请求，这里只要 1 个）。</p>
     */
    /**
     * 按日期定位：{@code GET /api/tool/web/locate-date?source=mrds&date=2026-11-01&size=24}
     *
     * <p>「日期筛选」不做过滤，而是跳到那一天在列表里的位置。算出该日期之后有多少条，
     * 除以每页条数就是落在第几页。</p>
     */
    @GetMapping("/locate-date")
    public Map<String, Object> locateDate(@RequestParam("date") String date,
            @RequestParam(value = "source", required = false, defaultValue = "mrds") String source,
            @RequestParam(value = "size", required = false, defaultValue = "24") int size,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader)
    {
        int safeSize = size < 1 ? 24 : Math.min(size, SIZE_MAX);
        String day = date == null ? "" : date.trim();
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}"))
        {
            return Map.of("page", 1, "before", 0, "total", toolWebPostMapper.countPage(source, null, null, null,
                    List.of(), blankToNull(category), blankToNull(tag), List.of(), List.of(), null, null, null, null, null));
        }
        // 先找到"那天（含）之前最新的一条"，再用 countBefore 算页号：
        // countBefore 和列表用同一套排序规则，跨格式的 published_full 也不会算偏。
        String owner0 = webOwnerResolver.resolve(authorization, ownerHeader, null);
        List<String> blockTags0 = owner0 == null ? List.of() : toolWebPostMapper.selectBlockedTags(owner0);
        Long anchorId = toolWebPostMapper.selectIdByDate(source, day, blankToNull(category), blankToNull(tag),
                blockTags0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day);
        if (anchorId == null)
        {
            out.put("page", 1);
            out.put("before", 0);
            out.put("anchorId", null);
            return out;
        }
        // 列表会按归属键过滤被屏蔽的标签；这里不传会在偏移量里多算这些条目，算出来的页号就偏了
        String owner = webOwnerResolver.resolve(authorization, ownerHeader, null);
        List<String> blockTags = owner == null ? List.of() : toolWebPostMapper.selectBlockedTags(owner);
        long before = toolWebPostMapper.countBefore(anchorId, source, blankToNull(category), blankToNull(tag),
                List.of(), blockTags, null, null, null);
        out.put("page", (int) (before / safeSize) + 1);
        out.put("before", before);
        out.put("anchorId", anchorId);
        return out;
    }

    /**
     * 每天有多少条：{@code GET /api/tool/web/day-counts?from=2026-11-01&to=2026-12-31}
     *
     * <p>自定义月历的"每格显示当天数量"用，返回 {@code [{day:'2026-11-01', c:12}, …]}。</p>
     */
    @GetMapping("/day-counts")
    public Map<String, Object> dayCounts(@RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(value = "source", required = false, defaultValue = "mrds") String source,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tag", required = false) String tag)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", toolWebPostMapper.selectDayCounts(source, from, to, blankToNull(category), blankToNull(tag)));
        return out;
    }

    @GetMapping("/locate")
    public Map<String, Object> locate(@RequestParam("postId") Long postId,
            @RequestParam(value = "source", required = false, defaultValue = "mrds") String source,
            @RequestParam(value = "size", required = false, defaultValue = "24") int size,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "dateFrom", required = false) String dateFrom,
            @RequestParam(value = "dateTo", required = false) String dateTo,
            @RequestParam(value = "featured", required = false) String featured,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Ms-Owner", required = false) String ownerHeader)
    {
        int safeSize = size < 1 ? 24 : Math.min(size, SIZE_MAX);
        List<String> cleanTags = new ArrayList<>();
        if (tags != null)
        {
            for (String piece : tags.split(","))
            {
                String t = piece.trim();
                if (!t.isEmpty() && cleanTags.size() < 20 && !cleanTags.contains(t))
                {
                    cleanTags.add(t);
                }
            }
        }
        String owner = webOwnerResolver.resolve(authorization, ownerHeader, null);
        List<String> blockTags = owner == null ? List.of() : toolWebPostMapper.selectBlockedTags(owner);
        long offset = toolWebPostMapper.countBefore(postId, source, blankToNull(category), blankToNull(tag),
                cleanTags, blockTags, blankToNull(dateFrom), blankToNull(dateTo), blankToNull(featured));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offset", offset);
        out.put("page", (int) (offset / safeSize) + 1);
        out.put("size", safeSize);
        return out;
    }

    /** 正文块 JSON 字符串 → List（坏数据返回空列表，绝不让详情 500） */
    private static List<Map<String, Object>> parseBlocks(Object raw)
    {
        if (raw == null)
        {
            return List.of();
        }
        try
        {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(raw),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>()
                    {
                    });
        }
        catch (Exception e)
        {
            return List.of();
        }
    }

    /** 逗号串 → 列表（去空白、去重、保序） */
    private static List<String> splitTags(Object raw)
    {
        List<String> out = new ArrayList<>();
        if (raw == null)
        {
            return out;
        }
        for (String piece : String.valueOf(raw).split(","))
        {
            String tag = piece.trim();
            if (!tag.isEmpty() && !out.contains(tag))
            {
                out.add(tag);
            }
        }
        return out;
    }
}
