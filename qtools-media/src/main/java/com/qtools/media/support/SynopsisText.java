package com.qtools.media.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 作品简介生成用的小文本工具（P5.47）：长度按 **code point** 计、空白归一化、截断。
 *
 * <p>纯静态、无状态、不碰 Spring —— 单测与离线夹具直接调它，不需要起容器。</p>
 *
 * @author qtools
 */
public final class SynopsisText
{
    /** 生成结果的硬上限（字符数，不是字节数）：模板与本地模型同一口径 */
    public static final int MAX_CHARS = 200;

    /** 「不是信息」的占位词：出现在任何字段里都当作空，绝不写进简介 */
    private static final String[] NOISE = { "无", "暂无", "未知", "未分类", "未标注", "无标签", "null", "NULL", "undefined" };

    private SynopsisText()
    {
    }

    /** 字符数（code point）：CJK / emoji 各算 1，与站端 countSynopsisChars 同口径 */
    public static int codePoints(String text)
    {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** 去首尾空白 + 占位词归一化成空串（null / "无" / "暂无" → ""） */
    public static String clean(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String text = raw.replace('\u00a0', ' ').replace('\u3000', ' ').trim();
        if (text.isEmpty())
        {
            return "";
        }
        for (String noise : NOISE)
        {
            if (noise.equals(text))
            {
                return "";
            }
        }
        return text;
    }

    /**
     * 取前 max 个非空项、去重后拼成「A、B、C」
     *
     * @param max 最多几项（&lt;=0 视为不限）
     */
    public static String join(List<String> values, int max)
    {
        if (values == null || values.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String raw : values)
        {
            String text = clean(raw);
            if (text.isEmpty())
            {
                continue;
            }
            if (sb.indexOf(text) >= 0)
            {
                continue;
            }
            if (used > 0)
            {
                sb.append('、');
            }
            sb.append(text);
            used++;
            if (max > 0 && used >= max)
            {
                break;
            }
        }
        return sb.toString();
    }

    /** 时长：秒 → mm:ss（&gt;= 1 小时用 h:mm:ss）；null / 非正数 → ""（调用方据此整句省略） */
    public static String formatDuration(BigDecimal dur)
    {
        if (dur == null || dur.signum() <= 0)
        {
            return "";
        }
        long total = dur.setScale(0, RoundingMode.HALF_UP).longValue();
        if (total <= 0)
        {
            return "";
        }
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0)
        {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    /** 按字符数硬截断：留一个省略号，绝不截半个代理对 */
    public static String truncate(String text)
    {
        if (codePoints(text) <= MAX_CHARS)
        {
            return text;
        }
        int end = text.offsetByCodePoints(0, MAX_CHARS - 1);
        return text.substring(0, end) + "…";
    }
}
