package com.qtools.media.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 字幕解析（P5.53 交付 2）：把 {@code .srt / .vtt / .ass / .ssa} 变成「正文行 + 时间轴」。
 *
 * <h2>为什么必须自己解析</h2>
 *
 * <p>作品简介要的是**片子讲了什么**，字幕是唯一能直接拿到内容的素材。字幕文件里真正有用的是
 * 台词文本，占 90% 体积的是序号、时间轴、样式与定位标记；原样喂给模型，1.5B 模型会把这些噪声
 * 当成内容抄回来。</p>
 *
 * <h2>格式与容错</h2>
 *
 * <table border="1">
 *   <caption>支持的输入</caption>
 *   <tr><th>格式</th><td>{@code WebVTT}（含 {@code WEBVTT} 头、{@code NOTE / STYLE / REGION} 块、
 *       {@code -->} 后的 cue 设置、{@code <c.x>} 内联标签）、{@code SRT}（序号 + {@code -->} 时间码）、
 *       {@code ASS/SSA}（{@code Dialogue:} 十列 CSV，{@code {\an8\pos(...)}} 覆写块，{@code \N} 换行）</td></tr>
 *   <tr><th>编码</th><td>调用方给的是已解码字符串：BOM 在这里剥（{@code \uFEFF}），
 *       解码本身（UTF-8 / GB18030）由 {@link MediaSubtitleReader} 负责</td></tr>
 *   <tr><th>换行</th><td>CRLF / CR / LF 一律当换行；连续空行折叠</td></tr>
 *   <tr><th>多语言轨</th><td>同一条 cue 里的多行（日语原音 + 中文字幕）<b>逐行保留</b> ——
 *       两行都是内容，去重只去掉「与上一条完全相同」的重复轨</td></tr>
 *   <tr><th>超长文件</th><td>解析不设上限（只读文本），<b>取节选</b>才截断：见
 *       {@link SubtitleExcerpt}</td></tr>
 * </table>
 *
 * <p>纯静态、无状态、无 Spring：夹具（{@code p553-evidence/harness}）直接调它做单测级别的断言。</p>
 *
 * @author qtools
 */
public final class SubtitleParser
{
    /** 支持的扩展名（小写，含点） */
    private static final String[] EXTENSIONS = { ".srt", ".vtt", ".ass", ".ssa", ".sub" };

    /** 纯噪声行（只有符号/占位，不含语义）：{@code ♪ … - — ·} 这类，整行丢弃 */
    private static final String NOISE_CHARS = "♪♫…-—–·.,，。、!！?？~～ 　\t";

    private SubtitleParser()
    {
    }

    /** 字幕格式 */
    public enum Format
    {
        VTT, SRT, ASS, UNKNOWN
    }

    /** 一条字幕：起始毫秒（未知 = -1）+ 正文（可能多行，已去标签） */
    public static final class Cue
    {
        public final long startMs;

        public final long endMs;

        public final String text;

        public Cue(long startMs, long endMs, String text)
        {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    /** 解析结果：格式 + 有序 cue 列表（不可变视图由调用方自理） */
    public static final class Doc
    {
        public final Format format;

        public final List<Cue> cues;

        public Doc(Format format, List<Cue> cues)
        {
            this.format = format;
            this.cues = cues;
        }

        /** 正文行数 */
        public int lineCount()
        {
            return cues.size();
        }

        /** 正文总字数（code point） */
        public int chars()
        {
            int sum = 0;
            for (Cue cue : cues)
            {
                sum += SynopsisText.codePoints(cue.text);
            }
            return sum;
        }

        public boolean isEmpty()
        {
            return cues.isEmpty();
        }
    }

    /**
     * 按扩展名判断格式（文件名大小写不敏感；不认识的扩展名回 {@link Format#UNKNOWN}，
     * 由 {@link #parse(String)} 按内容再猜一次）
     */
    public static Format formatOf(String fileName)
    {
        if (fileName == null)
        {
            return Format.UNKNOWN;
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0)
        {
            return Format.UNKNOWN;
        }
        String ext = lower.substring(dot);
        for (String known : EXTENSIONS)
        {
            if (!known.equals(ext))
            {
                continue;
            }
            if (".ass".equals(known) || ".ssa".equals(known))
            {
                return Format.ASS;
            }
            return ".vtt".equals(known) ? Format.VTT : Format.SRT;
        }
        return Format.UNKNOWN;
    }

    /** 解析（格式按内容自动判断） */
    public static Doc parse(String raw)
    {
        return parse(raw, Format.UNKNOWN);
    }

    /**
     * 解析字幕正文
     *
     * @param raw    已解码的文本（可带 BOM）；null / 空 → 空 Doc
     * @param hint   扩展名推断的格式；{@link Format#UNKNOWN} 时按内容猜
     */
    public static Doc parse(String raw, Format hint)
    {
        if (raw == null || raw.isEmpty())
        {
            return new Doc(hint == null ? Format.UNKNOWN : hint, new ArrayList<Cue>());
        }
        String text = normalize(raw);
        String[] lines = text.split("\n", -1);
        Format format = (hint == null || hint == Format.UNKNOWN) ? detect(lines) : hint;
        return format == Format.ASS ? parseAss(lines, format) : parseText(lines, format);
    }

    // ------------------------------------------------------------------ 归一化

    /**
     * 统一空白与换行：剥 BOM、CRLF/CR → LF、NBSP/全角空格 → 半角、去零宽字符
     *
     * <p>BOM 必须在这里剥：Windows 上用记事本另存的字幕常常带 {@code \uFEFF}，
     * 它会让第一行变成 {@code \uFEFFWEBVTT}，于是「是否是 VTT」的判定失效。</p>
     */
    public static String normalize(String raw)
    {
        String text = raw;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF')
        {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace('\u00a0', ' ').replace('\u3000', ' ');
        text = text.replace("\u200b", "").replace("\u200c", "").replace("\u200d", "").replace("\ufeff", "");
        return text;
    }

    /** 内容嗅探：ASS 的 {@code [Script Info]} / {@code Dialogue:}、VTT 的 {@code WEBVTT}、否则按 SRT */
    private static Format detect(String[] lines)
    {
        for (int i = 0; i < lines.length && i < 40; i++)
        {
            String line = lines[i].trim();
            if (line.isEmpty())
            {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("WEBVTT"))
            {
                return Format.VTT;
            }
            if (upper.startsWith("[SCRIPT INFO]") || upper.startsWith("DIALOGUE:"))
            {
                return Format.ASS;
            }
            if (upper.contains("-->"))
            {
                return Format.SRT;
            }
        }
        return Format.UNKNOWN;
    }

    // ------------------------------------------------------------------ SRT / VTT

    /**
     * SRT / VTT 通用解析：跳过序号行与时间码行，其余都是正文
     *
     * <p>不逐块解析（cue 块边界不可靠：有的 SRT 缺空行，有的 VTT 带 cue id），
     * 改成「只认三类行」：时间码行（含 {@code -->}，顺带取起始时间）、纯数字序号行、
     * 其余正文行。这样缺空行 / 多空行 / 带 id 的变体都不影响正文提取。</p>
     */
    private static Doc parseText(String[] lines, Format format)
    {
        List<Cue> cues = new ArrayList<>();
        long currentStart = -1;
        long currentEnd = -1;
        List<String> pending = new ArrayList<>();
        boolean inNote = false;
        for (String rawLine : lines)
        {
            String line = rawLine.trim();
            if (line.isEmpty())
            {
                flush(cues, pending, currentStart, currentEnd);
                currentStart = -1;
                currentEnd = -1;
                inNote = false;
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            // NOTE / STYLE / REGION 块：整块跳过，直到空行
            if (upper.equals("NOTE") || upper.startsWith("NOTE ") || upper.startsWith("STYLE") || upper.startsWith("REGION")
                    || upper.startsWith("WEBVTT") || upper.startsWith("KIND:") || upper.startsWith("LANGUAGE:"))
            {
                inNote = !upper.startsWith("WEBVTT") && !upper.startsWith("KIND:") && !upper.startsWith("LANGUAGE:");
                continue;
            }
            if (inNote)
            {
                continue;
            }
            if (line.contains("-->"))
            {
                flush(cues, pending, currentStart, currentEnd);
                long[] range = timeRangeOf(line);
                currentStart = range[0];
                currentEnd = range[1];
                continue;
            }
            if (isIndexLine(line))
            {
                continue;
            }
            String text = stripInline(line);
            if (isNoise(text) || isRepetitive(text))
            {
                continue;
            }
            pending.add(text);
        }
        flush(cues, pending, currentStart, currentEnd);
        return new Doc(format, cues);
    }

    /** 纯数字（SRT 序号 / VTT cue id 后的序号行）；超过 6 位数字当正文（台词里的数字） */
    private static boolean isIndexLine(String line)
    {
        if (line.isEmpty() || line.length() > 6)
        {
            return false;
        }
        for (int i = 0; i < line.length(); i++)
        {
            if (!Character.isDigit(line.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    /** 把积压的正文行收成一条 cue（空行 / 新时间码都触发）；与上一条完全相同则丢弃（重复轨） */
    private static void flush(List<Cue> cues, List<String> pending, long start, long end)
    {
        if (pending.isEmpty())
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : pending)
        {
            if (sb.length() > 0)
            {
                sb.append(' ');
            }
            sb.append(line);
        }
        String text = SynopsisText.clean(sb.toString());
        pending.clear();
        if (text.isEmpty() || isNoise(text) || isRepetitive(text))
        {
            return;
        }
        if (!cues.isEmpty() && cues.get(cues.size() - 1).text.equals(text))
        {
            return;
        }
        cues.add(new Cue(start, end, text));
    }

    // ------------------------------------------------------------------ ASS / SSA

    /**
     * ASS/SSA 解析：只认 {@code Dialogue:} 行，取第 10 列文本
     *
     * <p>{@code Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text} ——
     * 前 9 个字段都可能含逗号吗？不可能（都是时间码 / 数字 / 样式名），所以按 9 次逗号切分后
     * <b>剩下的全部</b>都是 Text（这是 ASS 规范里唯一可靠的切法）。</p>
     */
    private static Doc parseAss(String[] lines, Format format)
    {
        List<Cue> cues = new ArrayList<>();
        for (String rawLine : lines)
        {
            String line = rawLine.trim();
            if (line.isEmpty())
            {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            if (!upper.startsWith("DIALOGUE:"))
            {
                continue;
            }
            String body = line.substring("Dialogue:".length());
            String[] parts = body.split(",", 10);
            if (parts.length < 10)
            {
                // 老 SSA（Marked=0, ...）也是 10 列；列数不足只能按文本兜底
                continue;
            }
            long start = assTime(parts[1]);
            long end = assTime(parts[2]);
            String text = assText(parts[9]);
            if (isNoise(text) || isRepetitive(text))
            {
                continue;
            }
            if (!cues.isEmpty() && cues.get(cues.size() - 1).text.equals(text))
            {
                continue;
            }
            cues.add(new Cue(start, end, text));
        }
        return new Doc(format, cues);
    }

    /** {@code H:MM:SS.cc} → 毫秒；解析失败 -1 */
    static long assTime(String raw)
    {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty())
        {
            return -1;
        }
        String[] parts = text.split(":");
        if (parts.length != 3)
        {
            return -1;
        }
        try
        {
            long h = Long.parseLong(parts[0].trim());
            long m = Long.parseLong(parts[1].trim());
            double s = Double.parseDouble(parts[2].trim());
            return h * 3600000L + m * 60000L + (long) Math.round(s * 1000d);
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    /** ASS 文本：去 {@code {...}} 覆写块，{@code \N / \n} 当空格，去硬回车 */
    static String assText(String raw)
    {
        String text = raw == null ? "" : raw;
        text = text.replaceAll("\\{[^}]*\\}", " ");
        text = text.replace("\\N", " ").replace("\\n", " ").replace("\\h", " ");
        return SynopsisText.clean(stripInline(text));
    }

    // ------------------------------------------------------------------ 通用小工具

    /** 去内联标签：{@code <i> <b> <c.yellow> <00:00:01.000> <v Name>} 与残留花括号块 */
    static String stripInline(String line)
    {
        String text = line.replaceAll("<[^>]{1,80}>", "");
        text = text.replaceAll("\\{[^}]{1,120}\\}", "");
        return SynopsisText.clean(text);
    }

    /** SRT/VTT 时间码行 → {起始毫秒, 结束毫秒}；解析失败 {-1, -1} */
    static long[] timeRangeOf(String line)
    {
        String[] parts = line.split("-->", 2);
        long start = timecode(parts[0]);
        long end = parts.length > 1 ? timecode(parts[1]) : -1;
        return new long[] { start, end };
    }

    /**
     * {@code 00:00:12.340} / {@code 00:00:12,340} / {@code 12.34} → 毫秒
     *
     * <p>结束时间后面常跟着 VTT 的 cue 设置（{@code align:start position:10%}），
     * 所以只取第一个空白前的 token。</p>
     */
    static long timecode(String raw)
    {
        String text = raw == null ? "" : raw.trim();
        int space = text.indexOf(' ');
        if (space > 0)
        {
            text = text.substring(0, space);
        }
        if (text.isEmpty())
        {
            return -1;
        }
        text = text.replace(',', '.');
        String[] parts = text.split(":");
        try
        {
            if (parts.length == 3)
            {
                long h = Long.parseLong(parts[0].trim());
                long m = Long.parseLong(parts[1].trim());
                double s = Double.parseDouble(parts[2].trim());
                return h * 3600000L + m * 60000L + (long) Math.round(s * 1000d);
            }
            if (parts.length == 2)
            {
                long m = Long.parseLong(parts[0].trim());
                double s = Double.parseDouble(parts[1].trim());
                return m * 60000L + (long) Math.round(s * 1000d);
            }
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
        return -1;
    }

    /**
     * 是否「单字重复」行：{@code パチパチパチパチ…} / {@code 啪啪啪啪啪啪} 这类拟声长串
     *
     * <p>它们不是内容，但占了大量字数：一条 2000 字预算里混进几十个「啪啪啪」，
     * 1.5B 模型会把它当成主要情节抄进摘要（实测出现过）。连续同一个字 ≥ 5 次即判噪声 ——
     * 正常台词里「哈哈哈哈」到 5 个已经不像话了。</p>
     */
    static boolean isRepetitive(String text)
    {
        if (text == null || text.length() < 5)
        {
            return false;
        }
        int run = 1;
        for (int i = 1; i < text.length(); i++)
        {
            if (text.charAt(i) == text.charAt(i - 1))
            {
                run++;
                if (run >= 5)
                {
                    return true;
                }
            }
            else
            {
                run = 1;
            }
        }
        return false;
    }

    /** 是否噪声行：去标签后只剩符号（{@code ♪} / {@code …} / {@code -}）或只有 1 个字符的标点 */
    static boolean isNoise(String text)
    {
        if (text == null || text.isEmpty())
        {
            return true;
        }
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            if (NOISE_CHARS.indexOf(ch) < 0)
            {
                return false;
            }
        }
        return true;
    }
}
