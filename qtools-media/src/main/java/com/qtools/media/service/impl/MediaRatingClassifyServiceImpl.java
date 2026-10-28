package com.qtools.media.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRatingClassifyMapper;
import com.qtools.media.mapper.MediaRatingMapper;
import com.qtools.media.service.IMediaGuestAccessService;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaRatingClassifyService;
import com.qtools.media.support.MediaRating;
import com.qtools.media.support.MediaRatingLlmJudge;
import com.qtools.media.support.MediaRatingRules;

/**
 * 内容自动分级实现（P6.02）
 *
 * <h2>一次调用的完整流水线</h2>
 * <ol>
 *   <li><b>取数</b>：视频（标题 / 目录 / 相对路径 / 标签）+ 相册（标题 / 目录 / 名下照片 rel_path），
 *       全部按 id 升序 —— 同样输入两次跑出同样报告，才能拿报告跟人工抽查比对；</li>
 *   <li><b>规则层</b>：见 {@link MediaRatingRules}（最严优先，general 词不翻案）；</li>
 *   <li><b>模型层</b>：只对「规则一条未命中」的条目调用，并发 2、单条 15s，失败保持原值并进报告；</li>
 *   <li><b>写回</b>：默认只把 {@code general} 升级成更严的档（幂等：人工设过的 guidance / restricted
 *       不动，除非 {@code force=true}）；写完整批只失效一次缓存；</li>
 *   <li><b>出报告</b>：每档计数 / 命中词 Top20 / 20 条样例 / 模型兜底统计与 10 条样例。</li>
 * </ol>
 *
 * <h2>为什么写回不包在一个大事务里</h2>
 * <p>整轮可能包含几十次模型调用（每条最长 15s）。把它们包进一个事务，数据库连接会在模型推理期间
 * 被一直占着（Druid 池被一轮自动分级占满，站点其它接口全部排队）。写回是「按档位分组的 1-3 条
 * update」，单条语句本身原子，失败重跑幂等（默认不覆盖已升级的条目），因此不需要跨语句事务。</p>
 *
 * @author qtools
 */
@Service
public class MediaRatingClassifyServiceImpl implements IMediaRatingClassifyService
{
    private static final Logger log = LoggerFactory.getLogger(MediaRatingClassifyServiceImpl.class);

    /** 报告里给管理端看的样例条数（「前 20 条样例」） */
    private static final int SAMPLE_LIMIT = 20;

    /** 命中词统计的条数（每档混排，按命中条目数降序） */
    private static final int HIT_TOP = 20;

    /** 模型兜底样例条数（成功 / 失败各最多这么多） */
    private static final int LLM_SAMPLE_LIMIT = 10;

    /** 模型兜底的目标上限硬顶（配置再大也不超过它，防止一份错配置把 409 条全丢给 3B 模型） */
    private static final int LLM_HARD_CAP = 5000;

    @Autowired
    private MediaRatingClassifyMapper classifyMapper;

    /** 写入复用 P5.99 的写入口 SQL：谁在改分级只有一处语句 */
    @Autowired
    private MediaRatingMapper mediaRatingMapper;

    @Autowired
    private MediaProperties properties;

    @Autowired
    private MediaRatingLlmJudge llmJudge;

    @Autowired
    private IMediaGuestAccessService mediaGuestAccessService;

    @Autowired
    private IMediaIndexService mediaIndexService;

    @Override
    public Map<String, Object> classify(Boolean dryRun, String only, Integer limit, Boolean useLlm, Boolean force)
    {
        boolean apply = Boolean.FALSE.equals(dryRun);
        boolean withLlm = Boolean.TRUE.equals(useLlm);
        boolean overwrite = Boolean.TRUE.equals(force);
        String scope = normalizeOnly(only);
        int cap = limit == null || limit.intValue() < 0 ? 0 : limit.intValue();

        // 词表加载失败（文件不存在 / JSON 写坏 / 规则缺字段）必须是「能看懂的 503」而不是 500 internal error：
        // 唯一会碰上它的人就是刚改完 rating-rules.json 的站长，报错信息要直接指向文件与语法位置。
        MediaRatingRules rules;
        try
        {
            rules = MediaRatingRules.load(properties.getRating().getRulesFile());
        }
        catch (RuntimeException e)
        {
            throw MediaException.serviceUnavailable("分级词表加载失败：" + e.getMessage());
        }
        List<Item> items = new ArrayList<>();
        long begin = System.currentTimeMillis();
        if (!"album".equals(scope))
        {
            loadVideos(items, rules, cap);
        }
        if (!"video".equals(scope))
        {
            loadAlbums(items, rules, cap);
        }

        List<Item> uncertain = new ArrayList<>();
        for (Item item : items)
        {
            if (item.hits.isEmpty())
            {
                uncertain.add(item);
            }
        }
        Map<String, Object> llmNode = runLlm(uncertain, withLlm);

        resolve(items, overwrite);
        Map<String, Object> applied = writeBack(items, apply);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", Boolean.valueOf(!apply));
        out.put("only", scope);
        out.put("limit", cap == 0 ? null : Integer.valueOf(cap));
        out.put("useLlm", Boolean.valueOf(withLlm));
        out.put("force", Boolean.valueOf(overwrite));
        out.put("elapsedMs", Long.valueOf(System.currentTimeMillis() - begin));
        out.put("rules", rulesNode(rules));
        out.put("totals", totals(items));
        out.put("counts", counts(items, false));
        out.put("existing", counts(items, true));
        out.put("sources", sources(items));
        out.put("hitsTop", hitsTop(items));
        out.put("samples", samples(items));
        out.put("llm", llmNode);
        out.put("applied", applied);
        long ms = System.currentTimeMillis() - begin;
        log.info("自动分级完成 dryRun={} only={} items={} counts={} llmCandidates={} llmJudged={} ms={}",
                Boolean.valueOf(!apply), scope, Integer.valueOf(items.size()), out.get("counts"),
                Integer.valueOf(uncertain.size()), llmNode.get("judged"), Long.valueOf(ms));
        return out;
    }

    // ------------------------------------------------------------------ 取数 + 规则判定

    private void loadVideos(List<Item> items, MediaRatingRules rules, int cap)
    {
        List<Map<String, Object>> rows = classifyMapper.selectVideoRows();
        if (rows == null || rows.isEmpty())
        {
            return;
        }
        Map<String, List<String>> tags = new HashMap<>();
        List<Map<String, Object>> tagRows = classifyMapper.selectVideoTagRows();
        if (tagRows != null)
        {
            for (Map<String, Object> row : tagRows)
            {
                String videoId = text(row.get("videoId"));
                String name = text(row.get("name"));
                if (videoId.isEmpty() || name.isEmpty())
                {
                    continue;
                }
                tags.computeIfAbsent(videoId, key -> new ArrayList<>()).add(name);
            }
        }
        for (Map<String, Object> row : rows)
        {
            if (cap > 0 && items.size() >= cap)
            {
                break;
            }
            Item item = new Item();
            item.kind = "video";
            item.id = text(row.get("id"));
            item.title = text(row.get("title"));
            item.group = text(row.get("folder"));
            item.existing = MediaRating.normalize(text(row.get("contentRating")));
            List<MediaRatingRules.Field> fields = new ArrayList<>();
            fields.add(MediaRatingRules.Field.of("title", item.title));
            fields.add(MediaRatingRules.Field.of("folder", item.group));
            fields.add(MediaRatingRules.Field.of("path", text(row.get("relPath"))));
            for (String tag : tags.getOrDefault(item.id, Collections.emptyList()))
            {
                fields.add(MediaRatingRules.Field.of("tag", tag));
            }
            MediaRatingRules.Verdict verdict = rules.classify(fields);
            item.ruleRating = verdict.getRating();
            item.hits.addAll(verdict.getHits());
            item.reason = verdict.reason();
            items.add(item);
        }
    }

    private void loadAlbums(List<Item> items, MediaRatingRules rules, int cap)
    {
        List<Map<String, Object>> rows = classifyMapper.selectAlbumRows();
        if (rows == null || rows.isEmpty())
        {
            return;
        }
        Map<String, List<String>> photos = new HashMap<>();
        List<Map<String, Object>> photoRows = classifyMapper.selectAlbumPhotoRows();
        if (photoRows != null)
        {
            for (Map<String, Object> row : photoRows)
            {
                String albumId = text(row.get("albumId"));
                String path = text(row.get("relPath"));
                if (albumId.isEmpty() || path.isEmpty())
                {
                    continue;
                }
                photos.computeIfAbsent(albumId, key -> new ArrayList<>()).add(path);
            }
        }
        int added = 0;
        for (Map<String, Object> row : rows)
        {
            if (cap > 0 && added >= cap)
            {
                break;
            }
            Item item = new Item();
            item.kind = "album";
            item.id = text(row.get("id"));
            item.title = text(row.get("title"));
            item.group = text(row.get("dir"));
            item.existing = MediaRating.normalize(text(row.get("contentRating")));
            List<MediaRatingRules.Field> fields = new ArrayList<>();
            fields.add(MediaRatingRules.Field.of("title", item.title));
            fields.add(MediaRatingRules.Field.of("dir", item.group));
            MediaRatingRules.Verdict verdict = rules.classify(fields);
            item.ruleRating = verdict.getRating();
            item.hits.addAll(verdict.getHits());
            StringBuilder reason = new StringBuilder(verdict.reason());

            // 相册按内容聚合：逐张照片判 rel_path，取最严；照片数可能上千（019/130 两本），
            // 因此只保留「比当前更严」的那一张作为理由，不为每张照片攒命中明细。
            List<String> paths = photos.getOrDefault(item.id, Collections.emptyList());
            int totalPhotos = paths.size();
            for (int i = 0; i < totalPhotos; i++)
            {
                MediaRatingRules.Verdict photoVerdict = rules.classify(MediaRatingRules.Field.of("path", paths.get(i)));
                if (photoVerdict.isEmpty())
                {
                    continue;
                }
                int delta = MediaRatingRules.rank(photoVerdict.getRating()) - MediaRatingRules.rank(item.ruleRating);
                if (delta > 0)
                {
                    item.ruleRating = photoVerdict.getRating();
                    item.hits.addAll(photoVerdict.getHits());
                    MediaRatingRules.Hit hit = photoVerdict.strictestHit();
                    reason.append("；照片 #").append(i + 1).append("「").append(paths.get(i)).append("」命中『")
                            .append(hit == null ? "?" : hit.getWord()).append("』");
                }
            }
            if (totalPhotos == 0)
            {
                reason.append("；册内无照片，按目录名判定");
            }
            item.reason = reason.toString();
            item.photoCount = totalPhotos;
            items.add(item);
            added++;
        }
    }

    // ------------------------------------------------------------------ 模型兜底

    private Map<String, Object> runLlm(List<Item> uncertain, boolean enabled)
    {
        MediaProperties.Rating cfg = properties.getRating();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("enabled", Boolean.valueOf(enabled));
        node.put("ready", Boolean.valueOf(llmJudge.isReady()));
        node.put("model", llmJudge.modelName());
        node.put("provider", llmJudge.provider());
        node.put("timeoutMs", Integer.valueOf(cfg.getLlmTimeoutMs()));
        node.put("concurrency", Integer.valueOf(cfg.getLlmConcurrency()));
        node.put("candidates", Integer.valueOf(uncertain.size()));
        List<Map<String, Object>> judgedSamples = new ArrayList<>();
        List<Map<String, Object>> failedSamples = new ArrayList<>();
        long begin = System.currentTimeMillis();
        int judged = 0;
        int failed = 0;
        int truncated = 0;
        String note = "";
        if (enabled && !uncertain.isEmpty())
        {
            if (!llmJudge.isReady())
            {
                note = "本地模型未启用（qtools.media.llm.enabled=false），这些条目保持原值";
            }
            else
            {
                int budget = cfg.getLlmMaxItems();
                int cap = budget <= 0 ? LLM_HARD_CAP : Math.min(budget, LLM_HARD_CAP);
                List<Item> targets = uncertain.size() <= cap ? uncertain : uncertain.subList(0, cap);
                truncated = uncertain.size() - targets.size();
                ExecutorService pool = Executors.newFixedThreadPool(cfg.getLlmConcurrency());
                try
                {
                    List<Future<MediaRatingLlmJudge.Judgement>> futures = new ArrayList<>(targets.size());
                    for (Item item : targets)
                    {
                        futures.add(pool.submit(() -> llmJudge.judge(item.title, item.group)));
                    }
                    for (int i = 0; i < futures.size(); i++)
                    {
                        Item item = targets.get(i);
                        try
                        {
                            MediaRatingLlmJudge.Judgement judgement = futures.get(i)
                                    .get(cfg.getLlmTimeoutMs() + 3000L, TimeUnit.MILLISECONDS);
                            if (judgement == null)
                            {
                                failed++;
                                item.llmError = "模型回复里没有可用档位";
                                addLlmSample(failedSamples, item, null);
                                continue;
                            }
                            judged++;
                            item.source = "llm";
                            item.llmRating = judgement.getRating();
                            item.llmRaw = judgement.getRaw();
                            item.llmMs = judgement.getMs();
                            addLlmSample(judgedSamples, item, judgement);
                        }
                        catch (Exception e)
                        {
                            failed++;
                            item.llmError = brief(e.getMessage());
                            addLlmSample(failedSamples, item, null);
                            log.warn("自动分级：模型兜底失败 id={} kind={} err={}", item.id, item.kind, item.llmError);
                        }
                    }
                }
                finally
                {
                    pool.shutdownNow();
                }
                if (truncated > 0)
                {
                    note = "不确定条目超过配置上限（qtools.media.rating.llmMaxItems），本次只判前 "
                            + cap + " 条，其余保持原值";
                }
            }
        }
        else if (enabled)
        {
            note = "没有需要模型兜底的条目（规则全部命中）";
        }
        else if (!uncertain.isEmpty())
        {
            note = "本次未开启模型兜底（useLlm=false），这些条目保持原值";
        }
        node.put("judged", Integer.valueOf(judged));
        node.put("failed", Integer.valueOf(failed));
        node.put("truncated", Integer.valueOf(truncated));
        node.put("elapsedMs", Long.valueOf(System.currentTimeMillis() - begin));
        node.put("note", note);
        node.put("samples", judgedSamples);
        node.put("failedSamples", failedSamples);
        return node;
    }

    private void addLlmSample(List<Map<String, Object>> samples, Item item, MediaRatingLlmJudge.Judgement judgement)
    {
        if (samples.size() >= LLM_SAMPLE_LIMIT)
        {
            return;
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", item.kind);
        node.put("id", item.id);
        node.put("title", item.title);
        node.put("group", item.group);
        if (judgement == null)
        {
            node.put("rating", MediaRating.GENERAL);
            node.put("raw", item.llmError);
            node.put("ms", Long.valueOf(0L));
            node.put("ok", Boolean.FALSE);
        }
        else
        {
            node.put("rating", judgement.getRating());
            node.put("raw", judgement.getRaw());
            node.put("ms", Long.valueOf(judgement.getMs()));
            node.put("ok", Boolean.TRUE);
        }
        samples.add(node);
    }

    // ------------------------------------------------------------------ 定型 + 写回

    private void resolve(List<Item> items, boolean overwrite)
    {
        for (Item item : items)
        {
            if (!item.hits.isEmpty())
            {
                item.source = "rule";
                item.finalRating = item.ruleRating;
            }
            else if ("llm".equals(item.source) && item.llmRating != null)
            {
                item.finalRating = item.llmRating;
            }
            else
            {
                // 规则未命中且模型没有可用答复：保持原值（现网 = general），并在报告里例出
                item.source = "default";
                item.finalRating = item.existing;
            }
            item.changed = !item.finalRating.equals(item.existing);
            item.eligible = item.changed && (overwrite || MediaRating.GENERAL.equals(item.existing));
        }
    }

    private Map<String, Object> writeBack(List<Item> items, boolean apply)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        Map<String, List<String>> videoIds = new LinkedHashMap<>();
        Map<String, List<String>> albumIds = new LinkedHashMap<>();
        int eligible = 0;
        int protectedCount = 0;
        for (Item item : items)
        {
            if (item.eligible)
            {
                eligible++;
                Map<String, List<String>> target = "album".equals(item.kind) ? albumIds : videoIds;
                target.computeIfAbsent(item.finalRating, key -> new ArrayList<>()).add(item.id);
            }
            else if (item.changed)
            {
                // 已经是 guidance / restricted：默认不动（幂等的落脚点）
                protectedCount++;
            }
        }
        node.put("dryRun", Boolean.valueOf(!apply));
        node.put("eligible", Integer.valueOf(eligible));
        node.put("protected", Integer.valueOf(protectedCount));
        if (!apply)
        {
            node.put("videos", Integer.valueOf(0));
            node.put("albums", Integer.valueOf(0));
            node.put("updated", Integer.valueOf(0));
            return node;
        }
        int videos = 0;
        for (Map.Entry<String, List<String>> entry : videoIds.entrySet())
        {
            videos += mediaRatingMapper.updateVideosRating(entry.getValue(), entry.getKey());
        }
        int albums = 0;
        for (Map.Entry<String, List<String>> entry : albumIds.entrySet())
        {
            albums += mediaRatingMapper.updateAlbumsRating(entry.getValue(), entry.getKey());
        }
        if (videos + albums > 0)
        {
            // 分级决定站端可见性：两份快照一起失效（与单条 / 批量写入口同一套收尾）
            mediaGuestAccessService.invalidateRoots();
            mediaIndexService.invalidateSnapshot();
        }
        node.put("videos", Integer.valueOf(videos));
        node.put("albums", Integer.valueOf(albums));
        node.put("updated", Integer.valueOf(videos + albums));
        log.info("自动分级写库完成 videos={} albums={} eligible={} protected={}", Integer.valueOf(videos),
                Integer.valueOf(albums), Integer.valueOf(eligible), Integer.valueOf(protectedCount));
        return node;
    }

    // ------------------------------------------------------------------ 报告片段

    private Map<String, Object> rulesNode(MediaRatingRules rules)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("source", rules.getSource());
        node.put("total", Integer.valueOf(rules.size()));
        return node;
    }

    private Map<String, Object> totals(List<Item> items)
    {
        int videos = 0;
        int albums = 0;
        for (Item item : items)
        {
            if ("album".equals(item.kind))
            {
                albums++;
            }
            else
            {
                videos++;
            }
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("videos", Integer.valueOf(videos));
        node.put("albums", Integer.valueOf(albums));
        node.put("items", Integer.valueOf(items.size()));
        return node;
    }

    /** 每档计数；{@code existing=true} 取「跑之前」的分布，否则取「跑之后」的预期分布 */
    private Map<String, Object> counts(List<Item> items, boolean existing)
    {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String value : MediaRating.values())
        {
            tally.put(value, Integer.valueOf(0));
        }
        for (Item item : items)
        {
            String rating = existing ? item.existing : item.finalRating;
            tally.merge(MediaRating.normalize(rating), Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
        }
        Map<String, Object> node = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : tally.entrySet())
        {
            node.put(entry.getKey(), entry.getValue());
        }
        return node;
    }

    private Map<String, Object> sources(List<Item> items)
    {
        int rule = 0;
        int llm = 0;
        int fallback = 0;
        for (Item item : items)
        {
            if ("rule".equals(item.source))
            {
                rule++;
            }
            else if ("llm".equals(item.source))
            {
                llm++;
            }
            else
            {
                fallback++;
            }
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rule", Integer.valueOf(rule));
        node.put("llm", Integer.valueOf(llm));
        node.put("default", Integer.valueOf(fallback));
        return node;
    }

    /** 命中词统计：按「规则 id + 命中词」聚合，count = 命中该词的条目数 */
    private List<Map<String, Object>> hitsTop(List<Item> items)
    {
        Map<String, Map<String, Object>> tally = new LinkedHashMap<>();
        for (Item item : items)
        {
            Set<String> seen = new LinkedHashSet<>();
            for (MediaRatingRules.Hit hit : item.hits)
            {
                String key = hit.getRuleId() + "|" + hit.getWord();
                if (!seen.add(key))
                {
                    continue;
                }
                Map<String, Object> node = tally.get(key);
                if (node == null)
                {
                    node = new LinkedHashMap<>();
                    node.put("ruleId", hit.getRuleId());
                    node.put("word", hit.getWord());
                    node.put("rating", hit.getRating());
                    node.put("fields", new LinkedHashSet<String>());
                    node.put("count", Integer.valueOf(0));
                    tally.put(key, node);
                }
                @SuppressWarnings("unchecked")
                Set<String> fields = (Set<String>) node.get("fields");
                fields.add(hit.getFieldLabel());
                node.put("count", Integer.valueOf(((Integer) node.get("count")).intValue() + 1));
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(tally.values());
        list.sort((a, b) -> ((Integer) b.get("count")).intValue() - ((Integer) a.get("count")).intValue());
        List<Map<String, Object>> out = new ArrayList<>(Math.min(HIT_TOP, list.size()));
        for (int i = 0; i < list.size() && i < HIT_TOP; i++)
        {
            Map<String, Object> node = new LinkedHashMap<>(list.get(i));
            node.put("fields", new ArrayList<>((Set<?>) node.get("fields")));
            out.add(node);
        }
        return out;
    }

    /**
     * 前 20 条样例：按「档位 × 类型」六个桶轮流取
     *
     * <p>两个理由：</p>
     * <ol>
     *   <li>只按顺序取前 20 条会让样例几乎全是同一档（现网 general 占多数）；</li>
     *   <li>只按档位分桶也不够：视频有 239 条、相册 170 条，视频永远排在前面，
     *       按档位轮转时前 20 条会全是视频，相册的判定一条都抽查不到。
     *       所以桶的键是「限制级视频 / 限制级相册 / 家长指导视频 / …」，两类都在前 20 里出现。</li>
     * </ol>
     */
    private List<Map<String, Object>> samples(List<Item> items)
    {
        String[] tiers = { MediaRating.RESTRICTED, MediaRating.GUIDANCE, MediaRating.GENERAL };
        Map<String, List<Item>> buckets = new LinkedHashMap<>();
        for (String tier : tiers)
        {
            buckets.put(tier + "|video", new ArrayList<>());
            buckets.put(tier + "|album", new ArrayList<>());
        }
        for (Item item : items)
        {
            List<Item> bucket = buckets.get(MediaRating.normalize(item.finalRating) + "|" + item.kind);
            if (bucket != null)
            {
                bucket.add(item);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(SAMPLE_LIMIT);
        int round = 0;
        boolean added = true;
        while (out.size() < SAMPLE_LIMIT && added)
        {
            added = false;
            for (List<Item> bucket : buckets.values())
            {
                if (out.size() >= SAMPLE_LIMIT)
                {
                    break;
                }
                if (round < bucket.size())
                {
                    out.add(sampleNode(bucket.get(round)));
                    added = true;
                }
            }
            round++;
        }
        return out;
    }

    private Map<String, Object> sampleNode(Item item)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", item.kind);
        node.put("id", item.id);
        node.put("title", item.title);
        node.put("group", item.group);
        node.put("from", item.existing);
        node.put("rating", item.finalRating);
        node.put("label", MediaRating.labelOf(item.finalRating));
        node.put("source", item.source);
        node.put("changed", Boolean.valueOf(item.changed));
        node.put("reason", reasonOf(item));
        List<String> words = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MediaRatingRules.Hit hit : item.hits)
        {
            if (words.size() >= 4)
            {
                break;
            }
            if (seen.add(hit.getWord()))
            {
                words.add(hit.getWord());
            }
        }
        node.put("words", words);
        if (item.photoCount > 0)
        {
            node.put("photos", Integer.valueOf(item.photoCount));
        }
        if (item.llmError != null)
        {
            node.put("llmError", item.llmError);
        }
        return node;
    }

    private String reasonOf(Item item)
    {
        if ("llm".equals(item.source))
        {
            return "规则未命中 → 本地模型判定为 " + item.llmRating + "（模型回复：" + brief(item.llmRaw) + "）";
        }
        if ("default".equals(item.source))
        {
            return item.llmError == null ? "未命中任何规则词（默认大众级）"
                    : "未命中任何规则词，模型兜底失败（" + item.llmError + "），保持原值";
        }
        return item.reason == null ? "" : item.reason;
    }

    private static String normalizeOnly(String only)
    {
        String text = only == null ? "" : only.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || "all".equals(text))
        {
            return "all";
        }
        if ("video".equals(text) || "videos".equals(text))
        {
            return "video";
        }
        if ("album".equals(text) || "albums".equals(text))
        {
            return "album";
        }
        throw MediaException.badRequest("only 只能是 all / video / album，本次: " + only);
    }

    private static String text(Object value)
    {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String brief(String value)
    {
        if (value == null)
        {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    /** 一条待判定的条目（视频 / 相册各一条） */
    private static final class Item
    {
        private String kind;

        private String id;

        private String title;

        /** 视频 = folder，相册 = dir（报告里的「所属目录」） */
        private String group;

        private String existing;

        private String ruleRating = MediaRating.GENERAL;

        private String reason = "";

        private final List<MediaRatingRules.Hit> hits = new ArrayList<>();

        private String source = "default";

        private String finalRating;

        private boolean changed;

        private boolean eligible;

        private String llmRating;

        private String llmRaw;

        private String llmError;

        private long llmMs;

        private int photoCount;
    }
}
