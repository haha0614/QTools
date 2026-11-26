package com.qtools.tool.web.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * 双语 VTT 生成（docs/视频字幕-实施交接.md §T6）
 *
 * <p>纯函数、无依赖：喂 {@link Line} 列表进去，吐 VTT 文本出来。清洗与排版规则都在这里，
 * 服务层只负责「whisper cue + 译文 → Line」。</p>
 *
 * <h2>规则（按顺序）</h2>
 * <ol>
 *   <li>丢空行 / 纯标点 / 整行都是括号注释的片段，丢时长 &lt; 300ms 的碎片；</li>
 *   <li>与上一条日文完全相同、且间隔 &lt; 500ms 的合并成一条（whisper 的重复幻觉）；</li>
 *   <li>单条 &gt; 8 秒且日文 &gt; 40 字：按 、。！？ 切 2-3 条，时间按字数比例分；</li>
 *   <li>交叠修正：{@code cue[i].to = min(cue[i].to, cue[i+1].from)}，修完仍 &lt; 300ms 的丢掉；</li>
 *   <li>单行超过 42 全角宽：在最近的标点 / 助词后硬换行，每种语言最多 2 行（上日下中，最多 4 行）。</li>
 * </ol>
 *
 * @author qtools
 */
public final class SubtitleVtt
{
    /** 单行宽度上限（全角字符数；ASCII 算半格） */
    public static final int MAX_WIDTH = 42;

    /** 短于这个时长的片段没有可读性，直接丢 */
    public static final long MIN_MS = 300;

    /** 超过这个时长且字数够多就要切 */
    public static final long MAX_MS = 8000;

    /** 切分阈值：日文字数超过它才值得切 */
    public static final int LONG_CHARS = 40;

    /** 每种语言最多几行（上日文、下中文 → 整条最多 4 行） */
    public static final int MAX_LINES_PER_LANG = 2;

    /** 断行候选：标点后 / 助词后最自然 */
    private static final String BREAK_AFTER = "、。，．！？!?…‥,.;:：";

    private static final String PARTICLES = "てにをはがでともねよかなのりっうくすただしへやわを";

    /** 纯标点集合（判断「有没有可读正文」用） */
    private static final String PUNCT_ONLY = " \t　、。，．！？!?…‥・,.\"'“”‘’()（）[]【】<>「」『』-—–~～:：;；/\\|+*=_#@&$%^…♪♫";

    /** 一条字幕：起止毫秒 + 日文（上）+ 中文（下） */
    public static final class Line
    {
        public final long from;

        public final long to;

        public final String ja;

        public final String zh;

        public Line(long from, long to, String ja, String zh)
        {
            this.from = from;
            this.to = to;
            this.ja = ja == null ? "" : ja;
            this.zh = zh == null ? "" : zh;
        }
    }

    private SubtitleVtt()
    {
    }

    /** 生成 VTT 全文（含 {@code WEBVTT} 头） */
    public static String build(List<Line> raw)
    {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        for (Line cue : normalize(raw))
        {
            sb.append(timestamp(cue.from)).append(" --> ").append(timestamp(cue.to)).append('\n');
            for (String row : rows(cue))
            {
                sb.append(row).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 清洗 + 切分 + 交叠修正后的最终 cue 列表（服务层用它数 cue 条数） */
    public static List<Line> normalize(List<Line> raw)
    {
        List<Line> kept = new ArrayList<>();
        if (raw == null)
        {
            return kept;
        }
        for (Line line : raw)
        {
            if (line == null)
            {
                continue;
            }
            long from = Math.max(0, line.from);
            long to = line.to > from ? line.to : from + MIN_MS;
            String ja = line.ja.trim();
            String zh = line.zh.trim();
            if (!readable(ja) && !readable(zh))
            {
                continue;
            }
            if (to - from < MIN_MS)
            {
                continue;
            }
            if (!kept.isEmpty())
            {
                Line prev = kept.get(kept.size() - 1);
                if (!ja.isEmpty() && prev.ja.equals(ja) && from - prev.to < 500)
                {
                    // 同句重复：留前一条，尾巴延长到本条结束，中文缺了就用本条的
                    kept.set(kept.size() - 1, new Line(prev.from, Math.max(prev.to, to), prev.ja,
                            prev.zh.isEmpty() ? zh : prev.zh));
                    continue;
                }
            }
            kept.add(new Line(from, to, ja, zh));
        }
        List<Line> split = new ArrayList<>();
        for (Line line : kept)
        {
            split.addAll(splitLong(line));
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < split.size(); i++)
        {
            Line cur = split.get(i);
            long to = cur.to;
            if (i + 1 < split.size())
            {
                to = Math.min(to, split.get(i + 1).from);
            }
            if (to - cur.from < MIN_MS)
            {
                continue;
            }
            out.add(new Line(cur.from, to, cur.ja, cur.zh));
        }
        return out;
    }

    /** {@code 00:00:01.234} */
    public static String timestamp(long ms)
    {
        long safe = Math.max(0, ms);
        long hour = safe / 3600000L;
        long minute = safe % 3600000L / 60000L;
        long second = safe % 60000L / 1000L;
        long millis = safe % 1000L;
        return String.format("%02d:%02d:%02d.%03d", hour, minute, second, millis);
    }

    // ------------------------------------------------------------------ 切长句

    /** &gt; 8 秒且日文够长的句子按标点切成 2-3 条，时间按字数比例分 */
    private static List<Line> splitLong(Line line)
    {
        List<Line> one = new ArrayList<>(1);
        one.add(line);
        long dur = line.to - line.from;
        int chars = (int) Math.ceil(width(line.ja));
        if (dur <= MAX_MS || chars <= LONG_CHARS)
        {
            return one;
        }
        int parts = chars > LONG_CHARS * 2 ? 3 : 2;
        List<String> pieces = cutPieces(line.ja, parts);
        if (pieces.size() < 2)
        {
            return one;
        }
        List<Line> out = new ArrayList<>(pieces.size());
        double total = 0;
        for (String piece : pieces)
        {
            total += Math.max(1, width(piece));
        }
        long cursor = line.from;
        for (int i = 0; i < pieces.size(); i++)
        {
            String piece = pieces.get(i);
            long span = i == pieces.size() - 1 ? line.to - cursor
                    : Math.round(dur * (Math.max(1, width(piece)) / total));
            long end = i == pieces.size() - 1 ? line.to : Math.min(line.to, cursor + span);
            out.add(new Line(cursor, end, piece, ""));
            cursor = end;
        }
        // 切分只动日文：中文按整条给第一段（整条的中文仍是同一句的译文，放第一段最不容易错位）
        out.set(0, new Line(out.get(0).from, out.get(0).to, out.get(0).ja, line.zh));
        return out;
    }

    /** 按标点把日文切成最多 {@code parts} 段；找不到标点时按字数均分 */
    private static List<String> cutPieces(String text, int parts)
    {
        List<String> out = new ArrayList<>(parts);
        String rest = text.trim();
        while (out.size() < parts - 1 && !rest.isEmpty())
        {
            int target = (int) Math.ceil(width(rest) / (double) (parts - out.size()));
            int[] cut = new int[1];
            if (cutNear(rest, target, cut))
            {
                out.add(rest.substring(0, cut[0]).trim());
                rest = rest.substring(cut[0]).trim();
                continue;
            }
            int hard = indexAtWidth(rest, target);
            if (hard <= 0)
            {
                break;
            }
            out.add(rest.substring(0, hard).trim());
            rest = rest.substring(hard).trim();
        }
        if (!rest.isEmpty())
        {
            out.add(rest);
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    /** 找宽度最接近 target 的断点（标点之后），命中返回 true */
    private static boolean cutNear(String text, int target, int[] cut)
    {
        double acc = 0;
        int best = 0;
        double bestGap = Double.MAX_VALUE;
        int i = 0;
        while (i < text.length())
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            acc += widthOf(cp);
            if (BREAK_AFTER.indexOf(cp) >= 0)
            {
                double gap = Math.abs(acc - target);
                if (gap < bestGap && i < text.length())
                {
                    bestGap = gap;
                    best = i;
                }
            }
        }
        if (best <= 0)
        {
            return false;
        }
        cut[0] = best;
        return true;
    }

    /** 走到 target 宽度时的字符索引（硬切兜底） */
    private static int indexAtWidth(String text, int target)
    {
        double acc = 0;
        int i = 0;
        while (i < text.length())
        {
            int cp = text.codePointAt(i);
            int step = Character.charCount(cp);
            if (acc + widthOf(cp) > target)
            {
                break;
            }
            acc += widthOf(cp);
            i += step;
        }
        return i;
    }

    // ------------------------------------------------------------------ 排版

    /** 一条 cue 的正文行：先日文（上）后中文（下），每种最多 2 行 */
    private static List<String> rows(Line cue)
    {
        List<String> rows = new ArrayList<>(4);
        rows.addAll(wrap(cue.ja, MAX_LINES_PER_LANG));
        rows.addAll(wrap(cue.zh, MAX_LINES_PER_LANG));
        return rows;
    }

    /** 按 42 全角宽换行，最多 maxLines 行（超出部分并进最后一行，宁可挤也不要丢字） */
    static List<String> wrap(String text, int maxLines)
    {
        List<String> out = new ArrayList<>(maxLines);
        String rest = text == null ? "" : text.trim();
        while (!rest.isEmpty())
        {
            if (width(rest) <= MAX_WIDTH || out.size() >= maxLines - 1)
            {
                out.add(rest);
                break;
            }
            int cut = cutAt(rest);
            String head = rest.substring(0, cut).trim();
            if (head.isEmpty())
            {
                cut = Math.min(rest.length(), Character.charCount(rest.codePointAt(0)));
                head = rest.substring(0, cut).trim();
            }
            out.add(head);
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    /** 断点：优先标点 / 助词之后，退化到刚好不超宽的位置 */
    private static int cutAt(String text)
    {
        double acc = 0;
        int hard = 0;
        int soft = 0;
        int i = 0;
        while (i < text.length())
        {
            int cp = text.codePointAt(i);
            int step = Character.charCount(cp);
            double w = widthOf(cp);
            if (acc + w > MAX_WIDTH)
            {
                break;
            }
            acc += w;
            i += step;
            hard = i;
            if (acc >= MAX_WIDTH * 0.55 && (BREAK_AFTER.indexOf(cp) >= 0 || PARTICLES.indexOf(cp) >= 0))
            {
                soft = i;
            }
        }
        if (soft > 0)
        {
            return soft;
        }
        return hard > 0 ? hard : Math.min(text.length(), 1);
    }

    /** 显示宽度：全角算 1，ASCII / 半角算 0.5 */
    public static double width(String text)
    {
        if (text == null || text.isEmpty())
        {
            return 0;
        }
        double sum = 0;
        int i = 0;
        while (i < text.length())
        {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            sum += widthOf(cp);
        }
        return sum;
    }

    private static double widthOf(int cp)
    {
        return isWide(cp) ? 1.0 : 0.5;
    }

    /** 东亚宽字符（假名 / 汉字 / 全角标点 / 韩文 / 全角 ASCII） */
    private static boolean isWide(int cp)
    {
        return (cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0x303E) || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60) || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    // ------------------------------------------------------------------ 过滤

    /** 有没有可读正文：纯标点 / 只有 ♪♫ / 整行括号注释都算没有 */
    static boolean readable(String text)
    {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty())
        {
            return false;
        }
        if (value.startsWith("(") && value.endsWith(")") || value.startsWith("（") && value.endsWith("）")
                || value.startsWith("[") && value.endsWith("]") || value.startsWith("【") && value.endsWith("】"))
        {
            // (音楽) / [拍手] 这类整行注释不是台词，丢掉
            return false;
        }
        for (int i = 0; i < value.length();)
        {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp))
            {
                return true;
            }
            if (PUNCT_ONLY.indexOf(cp) < 0)
            {
                // 既不是字母数字、也不在标点表里（如 ♡♪）：不算正文
                continue;
            }
        }
        return false;
    }
}
