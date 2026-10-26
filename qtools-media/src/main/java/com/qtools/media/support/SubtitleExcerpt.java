package com.qtools.media.support;

import java.util.List;

/**
 * 字幕节选（P5.53 交付 2）：把一条片子的字幕压成「开头 2000 字 + 结尾 200 字」。
 *
 * <h2>为什么按字数而不是按时间</h2>
 *
 * <p>「取前 3 分钟」在字幕密度面前没有可比性：ASMR 中文音声大约 1 条 cue / 6 秒（3 分钟 ≈ 30 条
 * ≈ 300 字），动画番剧可以到 20 条 / 秒（3 分钟 ≈ 上万字，直接撑爆 1.5B 模型的上下文与注意力）。
 * 字数才是「模型要看多少东西」的真实计量，所以预算按 <b>code point</b> 算。</p>
 *
 * <h2>为什么还要留尾巴</h2>
 *
 * <p>只有开头时，模型看不到结果：很多片子前 3 分钟只是铺垫（入浴、换装、开场问候），
 * 真正的情节在最后。末尾 200 字足够给模型一个「结尾落在哪里」的锚点，又不至于把预算吃光。</p>
 *
 * <p>纯静态：夹具可离线断言截断行为（含 emoji 代理对不被截半个）。</p>
 *
 * @author qtools
 */
public final class SubtitleExcerpt
{
    /** 开头素材字数上限（code point） */
    public static final int HEAD_CHARS = 2000;

    /** 结尾素材字数上限（code point） */
    public static final int TAIL_CHARS = 200;

    /** 省略标记：让模型知道中间被跳过了（否则它会以为字幕到此为止） */
    static final String ELLIPSIS = "\n…（中段省略）…\n";

    private SubtitleExcerpt()
    {
    }

    /**
     * 素材最少字数：低于它就是「没内容可概括」
     *
     * <p>120 字是实测出来的下限：55 字的字幕（5 条 cue）交给 qwen2.5:1.5b，它会自己补出
     * 「厨房」「妻子」这种字幕里根本没有的情节（见 p553-evidence/api-subtitle.log 的对照）。
     * 宁可判 insufficient 让用户走模板，也不要编一段看起来很像的假梗概。</p>
     */
    public static final int MIN_MATERIAL_CHARS = 120;

    /** 是否值得喂给模型：太少就说明字幕只有零星几个字（片头字幕 / 只有一句），不足以概括 */
    public static boolean enough(String excerpt)
    {
        return SynopsisText.codePoints(excerpt) >= MIN_MATERIAL_CHARS;
    }

    /**
     * 取节选：开头 {@link #HEAD_CHARS} 字 + 结尾 {@link #TAIL_CHARS} 字
     *
     * @param doc 解析结果（null / 空 → ""）
     * @return 自然语言素材（cue 之间用换行分隔，中间超长时插省略标记）；从不为 null
     */
    public static String of(SubtitleParser.Doc doc)
    {
        return of(doc, HEAD_CHARS, TAIL_CHARS);
    }

    /** 取节选（自定义预算；夹具用它验证边界） */
    public static String of(SubtitleParser.Doc doc, int headChars, int tailChars)
    {
        if (doc == null || doc.cues.isEmpty())
        {
            return "";
        }
        String full = join(doc.cues, -1, -1);
        int headBudget = Math.max(1, headChars);
        int tailBudget = Math.max(0, tailChars);
        if (SynopsisText.codePoints(full) <= headBudget + tailBudget)
        {
            return full;
        }
        String head = join(doc.cues, headBudget, -1);
        String tail = tailOf(full, tailBudget);
        if (tailBudget <= 0 || tail.isEmpty() || head.contains(tail))
        {
            return head;
        }
        return head + ELLIPSIS + tail;
    }

    /**
     * 按字数预算拼 cue 文本
     *
     * @param budget 最多多少个 code point（&lt;= 0 = 不限）
     * @param skip   &gt; 0 时从末尾开始跳过这么多 code point（取尾巴时用不到，保留给调用方）
     */
    private static String join(List<SubtitleParser.Cue> cues, int budget, int skip)
    {
        StringBuilder sb = new StringBuilder(Math.min(4096, budget > 0 ? budget * 2 : 8192));
        int used = 0;
        for (SubtitleParser.Cue cue : cues)
        {
            String line = cue.text;
            if (line == null || line.isEmpty())
            {
                continue;
            }
            int len = SynopsisText.codePoints(line);
            // 换行也算预算：否则「前 2000 字」在 300 条 cue 的文件上会多出 300 个换行
            if (budget > 0 && used + len + 1 > budget)
            {
                int room = budget - used - 1;
                if (room > 1)
                {
                    sb.append(slice(line, 0, room)).append('\n');
                }
                break;
            }
            sb.append(line).append('\n');
            used += len + 1;
        }
        return trimEnd(sb.toString());
    }

    /** 全文最后 budget 个 code point（不足就全给） */
    private static String tailOf(String full, int budget)
    {
        if (budget <= 0)
        {
            return "";
        }
        int total = SynopsisText.codePoints(full);
        if (total <= budget)
        {
            return full;
        }
        String tail = slice(full, total - budget, budget).trim();
        // 从半句开始读着别扭：切到下一个句读点（有就切，没有就算了）
        int cut = -1;
        for (int i = 0; i < tail.length() && i < 12; i++)
        {
            char ch = tail.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '…')
            {
                cut = i + 1;
                break;
            }
        }
        return cut > 0 ? tail.substring(cut).trim() : tail;
    }

    /** 按 code point 切片（绝不切半个代理对；夹具与视觉素材都用它做安全截断） */
    public static String slice(String text, int fromCodePoint, int count)
    {
        if (text == null || text.isEmpty() || count <= 0)
        {
            return "";
        }
        int total = SynopsisText.codePoints(text);
        int start = Math.max(0, Math.min(fromCodePoint, total));
        int end = Math.max(start, Math.min(start + count, total));
        int startIdx = text.offsetByCodePoints(0, start);
        int endIdx = text.offsetByCodePoints(0, end);
        return text.substring(startIdx, endIdx);
    }

    private static String trimEnd(String text)
    {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1)))
        {
            end--;
        }
        return text.substring(0, end);
    }
}
