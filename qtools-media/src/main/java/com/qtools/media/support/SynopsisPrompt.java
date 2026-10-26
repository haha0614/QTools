package com.qtools.media.support;

import com.qtools.media.domain.dto.SynopsisFields;

/**
 * 本地模型生成的**固定提示词**（P5.47 起，P5.53 重写为「内容梗概」）
 *
 * <h2>P5.53 为什么要重写</h2>
 *
 * <p>旧的系统提示是「只依据给定的结构化字段，写 2-3 句作品简介」——素材只有标题 / 分类 / 时长 /
 * 标签 / 文件名，模型没有内容可总结，只能把标签换个说法抄一遍，于是产出了
 * 「作品名称：look-sit-16 作品分类：FRAC_桌面互动壁纸 时长：00:12 …」这种纯字段复述。
 * 根因不在模型大小，在<b>没有内容素材</b>。</p>
 *
 * <p>现在有三条素材路径，提示词各有专属版本：</p>
 * <ol>
 *   <li>{@link #subtitle(SynopsisFields, String)}：主素材 = 字幕节选（有字幕时优先）；</li>
 *   <li>{@link #vision(SynopsisFields, java.util.List)}：主素材 = 逐帧画面描述（无字幕时）；</li>
 *   <li>{@link #audio(SynopsisFields, String)}：主素材 = 音频转写（P5.57：字幕不可信时不信任字幕，
 *       直接识别片子的声音）。</li>
 * </ol>
 *
 * <p>两条都遵守同一套硬约束（{@link #SYSTEM}）：禁止罗列字段、禁止照抄标题、禁止写文件名与扩展名、
 * 禁止编造、素材不足只输出「信息不足」。元数据在提示词里只剩<b>一行体裁背景</b>，
 * 不再逐字段列出——这本身就是从「字段复述」到「内容摘要」的关键改动。</p>
 *
 * @author qtools
 */
public final class SynopsisPrompt
{
    /**
     * 系统提示（固定文案）：任何素材都不能改写它
     *
     * <p>「信息不足 → 只输出 信息不足」是硬约定：服务层靠 {@link SynopsisGuard#INSUFFICIENT_TOKEN}
     * 识别它并回 insufficient，而不是让模型硬编一段。</p>
     */
    public static final String SYSTEM = "你是影视内容编辑，只写片子的剧情梗概 / 内容摘要。\n"
            + "硬性规则：\n"
            + "1. 用 2-3 句自然语言说清「片中发生了什么 / 呈现了什么」，像简介，不像目录；\n"
            + "2. 严禁罗列字段，严禁出现「作品名称」「分类」「时长」「标签」「文件名」这类字段标签；\n"
            + "3. 严禁把台词逐条抄下来或编号列举，严禁照抄标题，严禁写出文件名、路径或扩展名（.mp4 / .webm / .srt 之类）；\n"
            + "4. 严禁编造素材里没有出现的人物、情节、地点、对白；\n"
            + "5. 不要写「这段字幕」「上述画面」「素材」这类元话术，不要加标题、编号、引号或 Markdown；\n"
            + "6. 素材可能有噪声、重复、多语混排，请忽略噪声，抓住人物在做什么、环境与气氛；\n"
            + "7. 只有素材里完全没有人类可读的对话或描述时，才只输出四个字：信息不足\n"
            + "8. 总长不超过 200 字。";

    /** 「信息不足」约定哨兵（与 {@link SynopsisGuard#INSUFFICIENT_TOKEN} 同值，这里是提示词侧引用） */
    public static final String INSUFFICIENT_TOKEN = SynopsisGuard.INSUFFICIENT_TOKEN;

    private SynopsisPrompt()
    {
    }

    /**
     * 字幕路线：主素材 = 字幕节选，元数据只作一句体裁背景
     *
     * @param f       结构化字段（只用到分类 / 时长做一句背景）
     * @param excerpt {@link SubtitleExcerpt} 的输出（开头 2000 字 + 结尾 200 字）
     */
    public static String subtitle(SynopsisFields f, String excerpt)
    {
        StringBuilder sb = new StringBuilder(Math.min(4096, SynopsisText.codePoints(excerpt) * 2 + 320));
        sb.append("下面是这部片子的字幕节选（台词 / 旁白，按时间顺序）。请据此写剧情梗概。\n");
        appendContext(sb, f);
        sb.append("字幕节选：\n");
        sb.append(excerpt == null ? "" : excerpt.trim()).append('\n');
        // 结尾只给**正向**指令：实测把这句写成「素材不足时只输出：信息不足」时，
        // qwen2.5:1.5b 会把它当成默认答案（2000 字中日混排素材一律回「信息不足」，4 个 token 就结束）。
        // 逃生门只放在系统提示第 7 条，且措辞收窄为「完全没有人类可读的对话」。
        sb.append("请输出这部片子的 2-3 句内容梗概。");
        return sb.toString();
    }

    /**
     * 视觉路线：主素材 = 逐帧画面描述（视觉模型对 4-6 帧各写一句）
     *
     * @param f       结构化字段（只用到分类 / 时长做一句背景）
     * @param frames  每帧一句描述（空描述已被调用方过滤；列表为空时不要调用本方法）
     */
    public static String vision(SynopsisFields f, java.util.List<String> frames)
    {
        StringBuilder sb = new StringBuilder(640);
        sb.append("下面是对这部片子抽取的若干帧画面的描述（按时间顺序）。请据此写内容梗概。\n");
        sb.append("不要逐帧罗列，也不要用顿号堆叠几个场景；请把画面合成为一段连贯的描述。\n");
        appendContext(sb, f);
        sb.append("画面描述：\n");
        int index = 0;
        for (String frame : frames)
        {
            String text = SynopsisText.clean(frame);
            if (text.isEmpty())
            {
                continue;
            }
            index++;
            sb.append(index).append(". ").append(text).append('\n');
        }
        sb.append("请输出这部片子的 2-3 句内容梗概。");
        return sb.toString();
    }

    /**
     * 音频转写路线：主素材 = whisper 转写文本（P5.57）
     *
     * <p>措辞与字幕路线有意区分：转写是<b>识别结果</b>，会有错词、漏句、复读，
     * 所以明确告诉模型「可能有识别错误」，让它抓内容而不是抄句子；同时保留硬约束
     * （不罗列、不照抄标题、不写文件名、不编造）。</p>
     *
     * @param f          结构化字段（只用到分类 / 时长做一句背景）
     * @param transcript 转写节选（{@link MediaTranscribeMaterial} 的 excerpt）
     */
    public static String audio(SynopsisFields f, String transcript)
    {
        StringBuilder sb = new StringBuilder(Math.min(4096, SynopsisText.codePoints(transcript) * 2 + 320));
        sb.append("下面是对这部片子音频的语音转写（说话人的对白 / 独白，按时间顺序）。请据此写内容梗概。\n");
        sb.append("转写来自自动语音识别，可能有错词、漏句或重复，请忽略这些噪声，抓住人物在做什么、场景与气氛。\n");
        appendContext(sb, f);
        sb.append("语音转写：\n");
        sb.append(transcript == null ? "" : transcript.trim()).append('\n');
        sb.append("请输出这部片子的 2-3 句内容梗概。");
        return sb.toString();
    }

    /**
     * 元数据路线（**只用于模板之外的人工对照**）：P5.53 起本地模型不再走这条路
     *
     * <p>保留它只是为了夹具能复现「旧提示词」的产物做前后对比，服务层不调用。</p>
     */
    public static String metadataOnly(SynopsisFields f)
    {
        StringBuilder sb = new StringBuilder(320);
        sb.append("作品简介素材（仅可使用以下信息）：\n");
        appendLine(sb, "标题", SynopsisText.clean(f.getTitle()));
        appendLine(sb, "分类", SynopsisText.clean(f.getFolder()));
        appendLine(sb, "时长", SynopsisText.formatDuration(f.getDur()));
        appendLine(sb, "人物", SynopsisText.join(f.getPersons(), 3));
        appendLine(sb, "标签", SynopsisText.join(f.getTags(), 5));
        appendLine(sb, "工作室", SynopsisText.join(f.getStudios(), 2));
        appendLine(sb, "文件名", fileName(f.getRelPath()));
        sb.append("请按系统要求输出简介正文。");
        return sb.toString();
    }

    /**
     * 体裁背景（一行）：只写「这是一部 X 类作品，时长 Y」，不作为可罗列的内容
     *
     * <p>刻意<b>不</b>写标题、标签、文件名：这三样正是模型拿来复述的东西。</p>
     */
    private static void appendContext(StringBuilder sb, SynopsisFields f)
    {
        String folder = SynopsisText.clean(f.getFolder());
        String dur = SynopsisText.formatDuration(f.getDur());
        if (folder.isEmpty() && dur.isEmpty())
        {
            return;
        }
        sb.append("背景（只供你判断体裁，不要写进梗概）：这是一部");
        if (!folder.isEmpty())
        {
            sb.append(folder).append('类');
        }
        sb.append("作品");
        if (!dur.isEmpty())
        {
            sb.append("，全长 ").append(dur);
        }
        sb.append("。\n");
    }

    /**
     * 清洗模型输出：压平空白 / 去整体包裹的引号 / 去「简介：」前缀 / 硬截断到 200 字符
     *
     * <p>「信息不足」哨兵原样保留（它必须能被 {@link SynopsisGuard} 识别）。</p>
     */
    public static String sanitize(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String text = raw.replace('\u00a0', ' ').replace('\u3000', ' ');
        text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        while (text.contains("  "))
        {
            text = text.replace("  ", " ");
        }
        text = text.trim();
        text = stripWrapping(text);
        text = stripPrefix(text, "作品简介：");
        text = stripPrefix(text, "作品简介:");
        text = stripPrefix(text, "简介：");
        text = stripPrefix(text, "简介:");
        text = stripPrefix(text, "内容梗概：");
        text = stripPrefix(text, "剧情梗概：");
        return SynopsisText.truncate(text.trim());
    }

    /** 句子/素材行：值为空就整行不写（空字段自动省略） */
    private static void appendLine(StringBuilder sb, String label, String value)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        sb.append(label).append('：').append(value).append('\n');
    }

    private static String stripPrefix(String text, String prefix)
    {
        return text.startsWith(prefix) ? text.substring(prefix.length()).trim() : text;
    }

    /** 整体被引号 / 书名号包住时剥掉一层（只剥一层，模型自己嵌套引号时不动） */
    private static String stripWrapping(String text)
    {
        if (text.length() < 2)
        {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        boolean wrap = (first == '「' && last == '」') || (first == '“' && last == '”')
                || (first == '《' && last == '》') || (first == '"' && last == '"')
                || (first == '\'' && last == '\'');
        return wrap ? text.substring(1, text.length() - 1).trim() : text;
    }

    /** 文件名（不含目录），进提示词用 */
    private static String fileName(String relPath)
    {
        if (relPath == null)
        {
            return "";
        }
        String text = relPath.replace('\\', '/');
        int idx = text.lastIndexOf('/');
        return (idx >= 0 ? text.substring(idx + 1) : text).trim();
    }
}
