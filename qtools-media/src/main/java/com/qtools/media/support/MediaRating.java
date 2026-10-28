package com.qtools.media.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.qtools.media.exception.MediaException;

/**
 * 内容分级（P5.99）—— <b>全站唯一</b>的分级枚举、中文名与校验
 *
 * <p>背景：{@code media_video.content_rating} / {@code media_album.content_rating} 两张表各存一份分级值，
 * {@code media_rating_policy.guest_visible} 再决定「这个分级的条目未登录能不能看见」。值域必须只有一处定义，
 * 否则「管理端能写进库的值」与「管理端筛选里列出的值」「站端 ratings[] 列出的值」会各自漂移，
 * 而漂移的后果是某档内容静默变成未分类（默认 general = 大众级，对访客可见）。</p>
 *
 * <p>因此这里集中三件事，别处一律不写字符串常量：</p>
 * <ol>
 *   <li>值域 + 中文名（{@link #LABELS}）：{@code general} 大众 / {@code guidance} 家长指导 / {@code restricted} 限制级；</li>
 *   <li>校验 {@link #parse(String)}：非法值 → 400（写接口唯一入口）；</li>
 *   <li>归一 {@link #normalize(String)}：读路径用，脏值当 {@code general}（宁可放宽可见，不静默藏内容）。</li>
 * </ol>
 *
 * <p><b>为什么读路径不抛异常</b>：分级列是本次新加的，历史行 / 未迁移库读到 {@code null} 必须能继续渲染索引；
 * 一个坏行不该让整个 {@code /media/index} 500（与 {@code MediaPersonServiceImpl.avatarUrlOf} 同一套取舍）。</p>
 *
 * @author qtools
 */
public final class MediaRating
{
    /** 大众级 */
    public static final String GENERAL = "general";

    /** 家长指导级 */
    public static final String GUIDANCE = "guidance";

    /** 限制级 */
    public static final String RESTRICTED = "restricted";

    /** 展示顺序（写入 {@code ratings[]} / 管理端下拉 / 策略面板都用它） */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static
    {
        LABELS.put(GENERAL, "大众级");
        LABELS.put(GUIDANCE, "家长指导级");
        LABELS.put(RESTRICTED, "限制级");
    }

    private MediaRating()
    {
    }

    /** 全部合法值（按展示顺序，恒非 null） */
    public static List<String> values()
    {
        return new ArrayList<>(LABELS.keySet());
    }

    /** 值 → 中文名；未知值原样返回（不抛异常，用于日志 / 管理端回显） */
    public static String labelOf(String value)
    {
        String clean = normalize(value);
        String label = LABELS.get(clean);
        return label == null ? clean : label;
    }

    /** 值 + 中文名的完整清单：{@code [{value,label}]}（管理端下拉 / 策略面板一次取齐） */
    public static List<Map<String, Object>> options()
    {
        List<Map<String, Object>> out = new ArrayList<>(LABELS.size());
        for (Map.Entry<String, String> entry : LABELS.entrySet())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("value", entry.getKey());
            node.put("label", entry.getValue());
            out.add(node);
        }
        return out;
    }

    /** 是否是合法值（大小写不敏感、去首尾空格） */
    public static boolean isValid(String value)
    {
        return value != null && LABELS.containsKey(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 归一：读路径用 —— {@code null} / 空白 / 非法值一律落 {@code general}
     *
     * <p>方向是<b>放宽可见</b>：脏值若当 {@code restricted} 处理，一个手改过的行就会让访客看不到正常内容。</p>
     */
    public static String normalize(String value)
    {
        if (value == null)
        {
            return GENERAL;
        }
        String clean = value.trim().toLowerCase(java.util.Locale.ROOT);
        return LABELS.containsKey(clean) ? clean : GENERAL;
    }

    /**
     * 校验：写路径唯一入口 —— 缺省 / 空白 / 非法值一律 400
     *
     * <p>刻意<b>不</b>接受 {@code null} 表示「清空」：分级的语义是「必须属于某一档」，
     * 清空等于回到大众级，前端直接传 {@code general} 即可，少一条隐式分支。</p>
     */
    public static String parse(String value)
    {
        String clean = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.isEmpty())
        {
            throw MediaException.badRequest("rating 必填，取值 " + allowed());
        }
        if (!LABELS.containsKey(clean))
        {
            throw MediaException.badRequest("rating 只能是 " + allowed() + "，本次: " + value);
        }
        return clean;
    }

    /** {@code general（大众级）/ guidance（家长指导级）/ restricted（限制级）} */
    public static String allowed()
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : LABELS.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append(" / ");
            }
            sb.append(entry.getKey()).append('（').append(entry.getValue()).append('）');
        }
        return sb.toString();
    }
}
