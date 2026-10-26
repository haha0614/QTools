package com.qtools.media.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.qtools.media.domain.dto.SynopsisFields;

/**
 * 模板生成（P5.47 交付 1）：纯函数，输入 {@link SynopsisFields}，输出 2-3 句中文。
 *
 * <h2>句式（最多三句，缺字段整句省略，绝不写「无」）</h2>
 * <ol>
 *   <li>主体句：{@code 小A、小B的剧情作品，时长 12:34。}；没有人名时退成 {@code 剧情作品，时长 12:34。}，
 *       连分类也没有时退成 {@code 时长 12:34 的作品。}，再退成 {@code 《标题》。}</li>
 *   <li>属性句：{@code 标签：剧情、室内，共 3 个时间点标记。}（标签 / 标记都没有 → 整句省略）</li>
 *   <li>署名句：{@code ACME 出品，主题：拍照、室内。}（工作室 / 文件名主题词都没有 → 整句省略）</li>
 * </ol>
 *
 * <p>长度上限 {@link SynopsisText#MAX_CHARS}（200 字符）：超限时从**最后一句**开始丢，
 * 主体句永远保留（简介没有主语就没有意义），仍超限才硬截断。</p>
 *
 * <p>「主题词」只从文件名里取中文片段，纯 ASCII 片段（{@code 1080p} / {@code 60fps} / {@code SSIS}）
 * 一律丢弃：那是分辨率与番号，不是内容。</p>
 *
 * @author qtools
 */
public final class SynopsisTemplateRenderer
{
    /** 主题词最多取几个（再多也只是把简介撑长） */
    private static final int MAX_THEMES = 3;

    /** 人物最多列几个 */
    private static final int MAX_PERSONS = 3;

    /** 标签最多列几个 */
    private static final int MAX_TAGS = 5;

    private SynopsisTemplateRenderer()
    {
    }

    /**
     * 渲染简介正文（永不为 null；素材全空时返回空串，由调用方决定是否回 404 / 400）
     */
    public static String render(SynopsisFields fields)
    {
        if (fields == null)
        {
            return "";
        }
        List<String> sentences = new ArrayList<>(3);
        String subject = subjectSentence(fields);
        if (!subject.isEmpty())
        {
            sentences.add(subject);
        }
        String attribute = attributeSentence(fields);
        if (!attribute.isEmpty())
        {
            sentences.add(attribute);
        }
        String credit = creditSentence(fields);
        if (!credit.isEmpty())
        {
            sentences.add(credit);
        }
        while (sentences.size() > 1 && SynopsisText.codePoints(concat(sentences)) > SynopsisText.MAX_CHARS)
        {
            sentences.remove(sentences.size() - 1);
        }
        return SynopsisText.truncate(concat(sentences));
    }

    /** 第一句：谁 + 什么分类 + 多长 */
    private static String subjectSentence(SynopsisFields f)
    {
        String persons = SynopsisText.join(f.getPersons(), MAX_PERSONS);
        String folder = SynopsisText.clean(f.getFolder());
        String dur = SynopsisText.formatDuration(f.getDur());
        StringBuilder sb = new StringBuilder();
        if (!persons.isEmpty())
        {
            sb.append(persons).append('的').append(folder).append("作品");
        }
        else if (!folder.isEmpty())
        {
            sb.append(folder).append("作品");
        }
        if (!dur.isEmpty())
        {
            if (sb.length() > 0)
            {
                sb.append("，时长 ").append(dur);
            }
            else
            {
                sb.append("时长 ").append(dur).append(" 的作品");
            }
        }
        if (sb.length() == 0)
        {
            String title = SynopsisText.clean(f.getTitle());
            return title.isEmpty() ? "" : "《" + title + "》。";
        }
        return sb.append('。').toString();
    }

    /** 第二句：标签 + 标记数（两者都没有就没有这一句） */
    private static String attributeSentence(SynopsisFields f)
    {
        String tags = SynopsisText.join(f.getTags(), MAX_TAGS);
        int markers = f.getMarkerCount();
        if (tags.isEmpty() && markers <= 0)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!tags.isEmpty())
        {
            sb.append("标签：").append(tags);
        }
        if (markers > 0)
        {
            if (sb.length() > 0)
            {
                sb.append('，');
            }
            sb.append("共 ").append(markers).append(" 个时间点标记");
        }
        return sb.append('。').toString();
    }

    /** 第三句：工作室 + 文件名主题词 */
    private static String creditSentence(SynopsisFields f)
    {
        String studios = SynopsisText.join(f.getStudios(), 2);
        String themes = SynopsisText.join(themes(f), MAX_THEMES);
        StringBuilder sb = new StringBuilder();
        if (!studios.isEmpty())
        {
            sb.append(studios).append(" 出品");
            if (!themes.isEmpty())
            {
                sb.append("，主题：").append(themes);
            }
        }
        else if (!themes.isEmpty())
        {
            sb.append("片中主题：").append(themes);
        }
        if (sb.length() == 0)
        {
            return "";
        }
        return sb.append('。').toString();
    }

    /** 文件名主题词：去掉已经出现在人物 / 标签 / 分类 / 工作室 / 标题里的词，避免同一句话里重复 */
    private static List<String> themes(SynopsisFields f)
    {
        List<String> known = new ArrayList<>();
        known.addAll(f.getPersons());
        known.addAll(f.getTags());
        known.addAll(f.getStudios());
        String folder = SynopsisText.clean(f.getFolder());
        if (!folder.isEmpty())
        {
            known.add(folder);
        }
        List<String> out = new ArrayList<>();
        for (String token : filenameTokens(f.getRelPath()))
        {
            boolean dup = false;
            for (String k : known)
            {
                if (k != null && !k.isEmpty() && k.contains(token))
                {
                    dup = true;
                    break;
                }
            }
            if (!dup)
            {
                out.add(token);
            }
            if (out.size() >= MAX_THEMES)
            {
                break;
            }
        }
        return out;
    }

    /**
     * 文件名 → 主题词候选（中文片段，去重，最多 6 个）
     *
     * <p>切分口径：先取 basename、去扩展名，再按「非字母 / 非数字 / 非汉字」切开；
     * 只保留 2-12 个字符且**含汉字**的片段，并丢掉「无」「未知」这类占位词。</p>
     */
    public static List<String> filenameTokens(String relPath)
    {
        String name = baseName(relPath);
        int dot = name.lastIndexOf('.');
        if (dot > 0)
        {
            name = name.substring(0, dot);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : name.split("[^0-9A-Za-z\\u4e00-\\u9fff]+"))
        {
            String token = SynopsisText.clean(part);
            if (token.isEmpty())
            {
                continue;
            }
            int len = SynopsisText.codePoints(token);
            if (len < 2 || len > 12)
            {
                continue;
            }
            if (!hasCjk(token))
            {
                continue;
            }
            out.add(token);
            if (out.size() >= 6)
            {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /** 路径最后一段（同时吃 / 与 \\） */
    private static String baseName(String relPath)
    {
        if (relPath == null)
        {
            return "";
        }
        String text = relPath.replace('\\', '/');
        int idx = text.lastIndexOf('/');
        return idx >= 0 ? text.substring(idx + 1) : text;
    }

    private static boolean hasCjk(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++)
        {
            char c = lower.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff)
            {
                return true;
            }
        }
        return false;
    }

    private static String concat(List<String> sentences)
    {
        StringBuilder sb = new StringBuilder();
        for (String s : sentences)
        {
            sb.append(s);
        }
        return sb.toString();
    }
}
