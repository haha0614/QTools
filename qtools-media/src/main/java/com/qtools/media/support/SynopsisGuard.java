package com.qtools.media.support;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 生成结果的**硬校验**（P5.53 交付 1）：把「字段复述」变成一种可判定的失败，而不是靠人眼看。
 *
 * <h2>为什么要有它</h2>
 *
 * <p>用户拿到的第一版结果是「作品名称：look-sit-16 作品分类：FRAC_桌面互动壁纸 时长：00:12 ……」——
 * 纯字段复述。提示词已经禁止了，但 1.5B 模型的服从性不稳定：<b>提示词是请求，校验才是保证</b>。
 * 校验不通过就当作「没有素材可概括」走 insufficient，绝不把垃圾写进简介框。</p>
 *
 * <h2>判据（按命中顺序）</h2>
 * <ol>
 *   <li>{@link Verdict#INSUFFICIENT}：模型输出了约定哨兵「信息不足」（这是<b>正确</b>行为，不是错误）</li>
 *   <li>{@link Verdict#FIELD_LIST}：出现「作品名称 / 分类：/ 时长：/ 标签：/ 文件名」这类字段标签</li>
 *   <li>{@link Verdict#FILE_NAME}：出现扩展名（{@code .mp4 / .webm / .srt …}）或磁盘文件名</li>
 *   <li>{@link Verdict#TITLE_COPY}：整段照抄标题（标题原样出现在正文里）</li>
 *   <li>{@link Verdict#TOO_SHORT}：少于 10 个字（说不了任何内容）</li>
 * </ol>
 *
 * <p>纯静态：夹具（{@code p553-evidence/harness}）对每一条判据都写反例断言。</p>
 *
 * @author qtools
 */
public final class SynopsisGuard
{
    /** 模型「素材不足」的约定哨兵：只输出这四个字，服务层据此回 insufficient */
    public static final String INSUFFICIENT_TOKEN = "信息不足";

    /** 生成正文的最短长度（code point）：低于它就是没写内容 */
    public static final int MIN_CHARS = 10;

    /**
     * 字段标签黑名单（含中英文冒号两种写法）
     *
     * <p>「时长」「分类」这类词本身在自然语言里也可能出现（「时长约 12 分钟」），
     * 所以只黑<b>带冒号的标签形态</b>与「作品名称/文件名」这种明确的字段名。</p>
     */
    private static final String[] FIELD_LABELS = { "作品名称", "作品分类", "作品时长", "作品标签", "作品简介",
            "文件名", "文件大小", "文件格式", "名称：", "名称:", "分类：", "分类:", "时长：", "时长:", "标签：", "标签:",
            "分辨率：", "分辨率:", "简介：", "简介:" };

    /** 扩展名黑名单（正文里出现 = 把文件名当内容写） */
    private static final String[] FILE_EXTENSIONS = { ".mp4", ".webm", ".mkv", ".avi", ".mov", ".wmv", ".flv",
            ".m4v", ".m2ts", ".rmvb", ".srt", ".vtt", ".ass", ".ssa", ".jpg", ".png" };

    private SynopsisGuard()
    {
    }

    /** 校验结论 */
    public enum Verdict
    {
        OK("ok"),
        INSUFFICIENT("insufficient"),
        FIELD_LIST("field_list"),
        FILE_NAME("file_name"),
        TITLE_COPY("title_copy"),
        TOO_SHORT("too_short"),
        UNGROUNDED("ungrounded");

        public final String code;

        Verdict(String code)
        {
            this.code = code;
        }
    }

    /** 结论说明（写进回包 reason / 日志，前端只显示一句人话） */
    public static String reason(Verdict verdict)
    {
        if (verdict == null)
        {
            return "";
        }
        switch (verdict)
        {
            case INSUFFICIENT:
                return "模型判断素材不足以概括内容";
            case FIELD_LIST:
                return "生成结果在罗列字段（作品名称 / 分类 / 时长 / 标签 / 文件名），已丢弃";
            case FILE_NAME:
                return "生成结果写出了文件名或扩展名，已丢弃";
            case TITLE_COPY:
                return "生成结果照抄了标题，已丢弃";
            case TOO_SHORT:
                return "生成结果过短，没有写出任何内容";
            case UNGROUNDED:
                return "生成结果与素材没有交集，疑似编造，已丢弃";
            default:
                return "";
        }
    }

    /** 校验（只看正文本身，不比对标题） */
    public static Verdict check(String text)
    {
        return check(text, null, null);
    }

    /**
     * 校验正文
     *
     * @param text     模型输出（已 sanitize）
     * @param title    库里的标题（用于「照抄标题」判据；null = 不比对）
     * @param relPath  库里的相对路径（用于「写出磁盘文件名」判据；null = 不比对）
     */
    public static Verdict check(String text, String title, String relPath)
    {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty())
        {
            return Verdict.INSUFFICIENT;
        }
        if (body.contains(INSUFFICIENT_TOKEN))
        {
            return Verdict.INSUFFICIENT;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String label : FIELD_LABELS)
        {
            if (body.contains(label))
            {
                return Verdict.FIELD_LIST;
            }
        }
        for (String ext : FILE_EXTENSIONS)
        {
            if (lower.contains(ext))
            {
                return Verdict.FILE_NAME;
            }
        }
        String baseName = baseNameOf(relPath);
        if (!baseName.isEmpty() && lower.contains(baseName.toLowerCase(Locale.ROOT)))
        {
            return Verdict.FILE_NAME;
        }
        String cleanTitle = SynopsisText.clean(title);
        if (SynopsisText.codePoints(cleanTitle) >= 4 && body.contains(cleanTitle))
        {
            return Verdict.TITLE_COPY;
        }
        if (SynopsisText.codePoints(body) < MIN_CHARS)
        {
            return Verdict.TOO_SHORT;
        }
        return Verdict.OK;
    }

    /**
     * 是否「有根据」：正文与素材至少共享一个 2-gram（中日韩片段）
     *
     * <p>编造与摘要的区别就在这里：摘要一定复用了素材里的词（人物、动作、场景），
     * 编造则是模型自己写一段流畅但无关的话。素材为空时不判定（无从比对）。</p>
     *
     * <p>阈值刻意宽松（共同 2-gram ≥ 1 即算有根据）：1.5B 模型会改写措辞，
     * 收紧到 3 个以上会把正常摘要也判掉；而完全不重合的输出基本只剩编造一种解释。</p>
     */
    public static boolean grounded(String text, String material)
    {
        if (material == null || material.trim().isEmpty())
        {
            return true;
        }
        Set<String> grams = bigrams(material);
        if (grams.isEmpty())
        {
            return true;
        }
        for (String gram : bigrams(text))
        {
            if (grams.contains(gram))
            {
                return true;
            }
        }
        return false;
    }

    /** 中日韩文字的 2-gram 集合（标点 / ASCII 数字不进 gram，避免「12」「, 」这种伪重合） */
    static Set<String> bigrams(String raw)
    {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null)
        {
            return out;
        }
        String text = raw;
        int len = text.length();
        for (int i = 0; i + 1 < len; i++)
        {
            char a = text.charAt(i);
            char b = text.charAt(i + 1);
            if (!isCjk(a) || !isCjk(b))
            {
                continue;
            }
            out.add(new String(new char[] { a, b }));
        }
        return out;
    }

    /** 汉字 / 假名 / 谚文（CJK 统一表意 + 扩展 A + 平假名片假名 + 谚文音节 + 兼容表意） */
    static boolean isCjk(char ch)
    {
        return (ch >= 0x4e00 && ch <= 0x9fff) || (ch >= 0x3400 && ch <= 0x4dbf) || (ch >= 0x3040 && ch <= 0x30ff)
                || (ch >= 0xac00 && ch <= 0xd7af) || (ch >= 0xf900 && ch <= 0xfaff);
    }

    /** 相对路径 → 磁盘文件名（不含扩展名，小写）：{@code a/b/x.mp4} → {@code x} */
    static String baseNameOf(String relPath)
    {
        if (relPath == null)
        {
            return "";
        }
        String text = relPath.replace('\\', '/').trim();
        int slash = text.lastIndexOf('/');
        String name = slash >= 0 ? text.substring(slash + 1) : text;
        int dot = name.lastIndexOf('.');
        if (dot > 0)
        {
            name = name.substring(0, dot);
        }
        return name.trim();
    }
}
