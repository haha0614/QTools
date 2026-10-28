package com.qtools.media.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内容分级规则引擎（P6.02）—— 纯函数、无 Spring 依赖，规则表在 {@code rating-rules.json}
 *
 * <h2>为什么规则表是资源文件而不是硬编码</h2>
 * <p>「哪些词算限制级」是站长可以随时调整的口径（今天「写真」到指导级，明天也许整包下架），
 * 硬编码进 Java 就得改代码重新发版。这里把<b>词表</b>放进 classpath 资源 {@link #DEFAULT_RESOURCE}，
 * 并允许 {@code qtools.media.rating.rulesFile} 指向外部同名文件覆盖 —— 外部文件按 mtime 变化重读，
 * 改词不必重启。</p>
 *
 * <h2>三条判定口径（与资源文件里的 _about 一一对应）</h2>
 * <ol>
 *   <li><b>最严优先</b>：命中多档取 {@code general < guidance < restricted} 中最大的一档，
 *       与命中顺序无关（不是「先命中先算」）；</li>
 *   <li><b>general 词不翻案</b>：general 与 restricted 同时命中一定取 restricted（宁可高估）；
 *       general 规则的作用只是让「为什么判大众级」在报告里可解释；</li>
 *   <li><b>一条不命中 = 不确定</b>：返回 {@link Verdict#isEmpty()}，由调用方决定交给本地模型还是保持原值。</li>
 * </ol>
 *
 * <p>字段维度（{@code title/folder/dir/path/tag}）刻意分开记录：报告里「命中词 + 命中字段」才是可解释的，
 * 只写「命中了自慰」而不说命中的是标题还是目录名，站长没法判断这条判得对不对。</p>
 *
 * @author qtools
 */
public final class MediaRatingRules
{
    /** 默认规则表（classpath 资源；{@code qtools.media.rating.rulesFile} 非空时改读外部文件） */
    public static final String DEFAULT_RESOURCE = "rating-rules.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 字段名 → 报告里的中文标签（顺序即报告顺序：标题 → 目录 → 路径 → 标签） */
    private static final Map<String, String> FIELD_LABELS = new LinkedHashMap<>();

    static
    {
        FIELD_LABELS.put("title", "标题");
        FIELD_LABELS.put("folder", "目录");
        FIELD_LABELS.put("dir", "目录");
        FIELD_LABELS.put("path", "路径");
        FIELD_LABELS.put("tag", "标签");
    }

    /** 一档的严格程度：数值越大越严格（冲突取最大） */
    private static final Map<String, Integer> RANK = new LinkedHashMap<>();

    static
    {
        RANK.put(MediaRating.GENERAL, Integer.valueOf(0));
        RANK.put(MediaRating.GUIDANCE, Integer.valueOf(1));
        RANK.put(MediaRating.RESTRICTED, Integer.valueOf(2));
    }

    /** 参与匹配的一个字段值（字段名 + 文本） */
    public static final class Field
    {
        private final String name;

        private final String value;

        private Field(String name, String value)
        {
            this.name = name;
            this.value = value;
        }

        /** 空值不建对象：没有值的字段不需要进匹配循环（也避免把 null 当字符串 "null"） */
        public static Field of(String name, String value)
        {
            if (name == null || value == null || value.trim().isEmpty())
            {
                return null;
            }
            return new Field(name, value);
        }

        public String getName()
        {
            return name;
        }

        public String getValue()
        {
            return value;
        }

        public String label()
        {
            String label = FIELD_LABELS.get(name);
            return label == null ? name : label;
        }
    }

    /** 一处命中：哪条规则、命中哪个词、命中哪个字段 */
    public static final class Hit
    {
        private final String ruleId;

        private final String rating;

        private final String word;

        private final String field;

        private final String fieldLabel;

        private final String note;

        Hit(String ruleId, String rating, String word, String field, String fieldLabel, String note)
        {
            this.ruleId = ruleId;
            this.rating = rating;
            this.word = word;
            this.field = field;
            this.fieldLabel = fieldLabel;
            this.note = note;
        }

        public String getRuleId()
        {
            return ruleId;
        }

        public String getRating()
        {
            return rating;
        }

        public String getWord()
        {
            return word;
        }

        public String getField()
        {
            return field;
        }

        public String getFieldLabel()
        {
            return fieldLabel;
        }

        public String getNote()
        {
            return note;
        }

        @Override
        public String toString()
        {
            return word + "（" + fieldLabel + "）";
        }
    }

    /** 一条规则 */
    private static final class Rule
    {
        private final String id;

        private final String rating;

        private final String mode;

        private final String pattern;

        private final Set<String> fields;

        private final String note;

        private final Pattern regex;

        private final String lowerPattern;

        private Rule(String id, String rating, String mode, String pattern, Set<String> fields, String note)
        {
            this.id = id;
            this.rating = rating;
            this.mode = mode;
            this.pattern = pattern;
            this.fields = fields;
            this.note = note;
            if ("regex".equals(mode))
            {
                this.regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                this.lowerPattern = null;
            }
            else
            {
                this.regex = null;
                this.lowerPattern = pattern;
            }
        }

        /** 命中时返回命中的原始词（regex 取实际匹配片段），未命中返回 null */
        private String match(String value)
        {
            if (value == null || value.isEmpty())
            {
                return null;
            }
            if (regex != null)
            {
                Matcher matcher = regex.matcher(value);
                return matcher.find() ? matcher.group() : null;
            }
            // contains：两侧都按 Locale.ROOT 小写，保证 NSFW / Hentai 这类英文词与大小写无关
            return value.toLowerCase(Locale.ROOT).contains(lowerPattern) ? pattern : null;
        }
    }

    /**
     * 判定结果
     *
     * <p>{@code hits} 为空 = 规则层没命中（调用方据此决定是否走本地模型）；不为空时
     * {@code rating} 恒等于 hits 里最严的那一档。</p>
     */
    public static final class Verdict
    {
        private final String rating;

        private final List<Hit> hits;

        Verdict(String rating, List<Hit> hits)
        {
            this.rating = rating;
            this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
        }

        public String getRating()
        {
            return rating;
        }

        public List<Hit> getHits()
        {
            return hits;
        }

        public boolean isEmpty()
        {
            return hits.isEmpty();
        }

        /** 参与判定的最严命中（报告里「为什么是这一档」就取它） */
        public Hit strictestHit()
        {
            Hit best = null;
            int bestRank = -1;
            for (Hit hit : hits)
            {
                int rank = rankOf(hit.getRating());
                if (rank > bestRank)
                {
                    best = hit;
                    bestRank = rank;
                }
            }
            return best;
        }

        /**
         * 可解释的判定理由：{@code 『自慰』（标题）} / {@code 『自慰』（标题）…等 3 处}
         *
         * <p>未命中时明确写「未命中任何规则词」，这样报告里「为什么是大众级」同样是可追溯的，
         * 而不是一片空白让人以为是默认值漏判。</p>
         */
        public String reason()
        {
            if (hits.isEmpty())
            {
                return "未命中任何规则词（默认大众级）";
            }
            // 按「词」去重：同一个词命中标题 + 目录 + 路径时报三遍（『自慰』（标题）、『自慰』（目录）…）
            // 只会淹没理由。保留第一次出现的字段，更适合人读。
            List<Hit> distinct = new ArrayList<>();
            Set<String> words = new LinkedHashSet<>();
            for (Hit hit : hits)
            {
                if (words.add(hit.getWord()))
                {
                    distinct.add(hit);
                }
            }
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(2, distinct.size());
            for (int i = 0; i < limit; i++)
            {
                if (sb.length() > 0)
                {
                    sb.append('、');
                }
                sb.append('『').append(distinct.get(i).getWord()).append('』')
                        .append('（').append(distinct.get(i).getFieldLabel()).append('）');
            }
            if (distinct.size() > limit)
            {
                sb.append("…等 ").append(distinct.size()).append(" 词");
            }
            return sb.toString();
        }
    }

    private final List<Rule> rules;

    private final String source;

    private MediaRatingRules(List<Rule> rules, String source)
    {
        this.rules = Collections.unmodifiableList(rules);
        this.source = source;
    }

    /** 规则来源（classpath:rating-rules.json 或外部绝对路径），报告里原样回显给站长 */
    public String getSource()
    {
        return source;
    }

    public int size()
    {
        return rules.size();
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 载入规则表
     *
     * @param pathOverride 外部规则文件路径（{@code qtools.media.rating.rulesFile}）；空白 = 读 classpath 资源
     * @throws IllegalStateException 文件不存在 / JSON 非法 / 规则缺字段（宁可启动即报错，也不要静默用空词表把全站判成大众级）
     */
    public static MediaRatingRules load(String pathOverride)
    {
        String path = pathOverride == null ? "" : pathOverride.trim();
        if (!path.isEmpty())
        {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file))
            {
                throw new IllegalStateException("分级规则文件不存在: " + file
                        + "（qtools.media.rating.rulesFile 指向的文件必须可读）");
            }
            try
            {
                return fromJson(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            }
            catch (IOException e)
            {
                throw new IllegalStateException("分级规则文件读取失败: " + file + "：" + e.getMessage(), e);
            }
        }
        ClassLoader loader = MediaRatingRules.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(DEFAULT_RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("classpath 缺少 " + DEFAULT_RESOURCE + "（qtools-media 资源未打包？）");
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), "classpath:" + DEFAULT_RESOURCE);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("classpath:" + DEFAULT_RESOURCE + " 读取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 文本构造（公开给单测：纯函数，不碰文件系统）
     *
     * @param json   {@code rating-rules.json} 的内容
     * @param source 来源描述（回显进报告）
     */
    public static MediaRatingRules fromJson(String json, String source)
    {
        JsonNode root;
        try
        {
            root = MAPPER.readTree(json);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("分级规则 JSON 解析失败: " + e.getMessage(), e);
        }
        JsonNode nodes = root.get("rules");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty())
        {
            throw new IllegalStateException("分级规则表里没有 rules 数组（空词表会把全站判成大众级，拒绝加载）");
        }
        List<Rule> parsed = new ArrayList<>(nodes.size());
        Set<String> ids = new HashSet<>();
        for (JsonNode node : nodes)
        {
            String id = text(node, "id");
            String rawRating = text(node, "rating");
            String rating = MediaRating.normalize(rawRating);
            if (id.isEmpty())
            {
                throw new IllegalStateException("分级规则缺少 id: " + node);
            }
            if (!ids.add(id))
            {
                throw new IllegalStateException("分级规则 id 重复: " + id + "（命中统计按 id 聚合，不允许重名）");
            }
            // 先校验原始值再归一：MediaRating.normalize 会把非法值悄悄落成 general，
            // 词表里写错档位（例如 "explicit"）必须报错，而不是静默当成大众级
            if (!MediaRating.isValid(rawRating))
            {
                throw new IllegalStateException(
                        "分级规则的 rating 只能是 general/guidance/restricted: " + id + " → " + rawRating);
            }
            String mode = text(node, "mode");
            if (!"regex".equals(mode) && !"contains".equals(mode))
            {
                throw new IllegalStateException("分级规则 mode 只能是 regex 或 contains: " + id);
            }
            String pattern = text(node, "pattern");
            if (pattern.isEmpty())
            {
                throw new IllegalStateException("分级规则缺少 pattern: " + id);
            }
            Set<String> fields = new LinkedHashSet<>();
            JsonNode fieldNode = node.get("fields");
            if (fieldNode != null && fieldNode.isArray())
            {
                for (JsonNode one : fieldNode)
                {
                    String name = one.asText("").trim();
                    if (!FIELD_LABELS.containsKey(name))
                    {
                        throw new IllegalStateException("分级规则字段未知: " + id + " → " + name
                                + "（可选 title/folder/dir/path/tag）");
                    }
                    fields.add(name);
                }
            }
            if (fields.isEmpty())
            {
                fields.addAll(FIELD_LABELS.keySet());
            }
            try
            {
                parsed.add(new Rule(id, rating, mode, pattern, fields, text(node, "note")));
            }
            catch (RuntimeException e)
            {
                throw new IllegalStateException("分级规则正则写错: " + id + " → " + pattern + "：" + e.getMessage(), e);
            }
        }
        return new MediaRatingRules(parsed, source == null ? "" : source);
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    // ------------------------------------------------------------------ 判定

    /** 判定一组字段值（顺序无关；同一规则在同一字段上只记一次） */
    public Verdict classify(List<Field> fields)
    {
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String rating = MediaRating.GENERAL;
        int best = -1;
        if (fields != null)
        {
            for (Rule rule : rules)
            {
                for (Field field : fields)
                {
                    if (field == null || !rule.fields.contains(field.getName()))
                    {
                        continue;
                    }
                    String word = rule.match(field.getValue());
                    if (word == null)
                    {
                        continue;
                    }
                    if (!seen.add(rule.id + "|" + field.getName()))
                    {
                        continue;
                    }
                    hits.add(new Hit(rule.id, rule.rating, word, field.getName(), field.label(), rule.note));
                    int rank = rankOf(rule.rating);
                    if (rank > best)
                    {
                        best = rank;
                        rating = rule.rating;
                    }
                }
            }
        }
        return new Verdict(rating, hits);
    }

    /** 可变参数版本（单条视频 / 单张照片的常见用法） */
    public Verdict classify(Field... fields)
    {
        List<Field> list = new ArrayList<>(fields == null ? 0 : fields.length);
        if (fields != null)
        {
            for (Field field : fields)
            {
                if (field != null)
                {
                    list.add(field);
                }
            }
        }
        return classify(list);
    }

    /**
     * 两批判定的合并：取更严的一批
     *
     * <p>相册就是靠它聚合的：册本身的标题/目录判一次，名下每张照片的 rel_path 各判一次，
     * 一路合并下来就是「册里最严的那张照片」。更严的一批没有被更宽松的一批污染 ——
     * 这也是「命中 general 词不会把 restricted 顶掉」在聚合层的体现。</p>
     */
    public static Verdict strictest(Verdict left, Verdict right)
    {
        if (left == null)
        {
            return right;
        }
        if (right == null)
        {
            return left;
        }
        int l = rankOf(left.rating);
        int r = rankOf(right.rating);
        if (r > l)
        {
            return right;
        }
        if (r < l)
        {
            return left;
        }
        // 同档：合并命中明细，让理由里能看到两侧的词
        List<Hit> merged = new ArrayList<>(left.hits);
        Set<String> keys = new HashSet<>();
        for (Hit hit : merged)
        {
            keys.add(hit.getRuleId() + "|" + hit.getField());
        }
        for (Hit hit : right.hits)
        {
            if (keys.add(hit.getRuleId() + "|" + hit.getField()))
            {
                merged.add(hit);
            }
        }
        return new Verdict(left.rating, merged);
    }

    /** 严格程度（0 大众 / 1 家长指导 / 2 限制级）；未知值按 0 —— 相册聚合与「是否更严」的比较都用它 */
    public static int rank(String rating)
    {
        Integer value = RANK.get(MediaRating.normalize(rating));
        return value == null ? 0 : value.intValue();
    }

    private static int rankOf(String rating)
    {
        return rank(rating);
    }
}
