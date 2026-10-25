package com.qtools.media.support;

import com.qtools.media.exception.MediaException;

/**
 * 夜间静默区间（{@code quietMinutes}）的解析与校验
 *
 * <p>同一个值要过三道关：<b>写入时</b>必须校验（非法 400，不能把坏值落库）、<b>启动时</b>读库必须
 * 校验（坏值回退 yml 兜底并 warn，不能静默变成「全天静默」）、<b>运行期</b>每次判区间必须容错
 * （解析失败只能当作不静默，绝不能让监听线程抛异常）。三处规则必须逐字相同，所以只保留这一份。</p>
 *
 * <p>两种合法写法：{@code HH:mm-HH:mm}（如 {@code 23:00-07:00}）或当日分钟数
 * {@code 起始-结束}（如 {@code 1380-420}）；跨零点即「起始 &gt; 结束」。空串 = 不静默。</p>
 *
 * @author qtools
 */
public final class MediaQuietWindow
{
    private MediaQuietWindow()
    {
    }

    /**
     * 严格校验（写库前用）：空 / null 通过（= 不静默），非法抛 400
     *
     * @return 归一化后的原文（去首尾空格；空 → 空串），调用方直接落库
     */
    public static String requireValid(String text)
    {
        if (text == null || text.trim().isEmpty())
        {
            return "";
        }
        String raw = text.trim();
        if (parse(raw) == null)
        {
            throw MediaException.badRequest("quietMinutes 格式非法，应为 HH:mm-HH:mm（如 23:00-07:00）"
                    + "或当日分钟数 起始-结束（如 1380-420，跨零点允许），本次: " + raw);
        }
        return raw;
    }

    /**
     * 宽松解析（读库 / 运行期用）：空 / 非法一律返回 null（= 不静默），非法时只打一条 warn
     *
     * @return {@code [start,end]}（当日分钟数，0-1440）；不静默或非法返回 null
     */
    public static int[] parse(String text)
    {
        if (text == null || text.trim().isEmpty())
        {
            return null;
        }
        String raw = text.trim();
        int dash = raw.indexOf('-');
        if (dash <= 0 || dash == raw.length() - 1)
        {
            return null;
        }
        Integer start = minuteOf(raw.substring(0, dash));
        Integer end = minuteOf(raw.substring(dash + 1));
        if (start == null || end == null)
        {
            return null;
        }
        return new int[] { start.intValue(), end.intValue() };
    }

    /** 当前时刻是否落在区间内，在区间内返回距结束的毫秒数（少退 1 秒避免边界反复重排），否则 0 */
    public static long millisRemaining(int[] window, java.time.LocalTime now)
    {
        if (window == null || now == null)
        {
            return 0L;
        }
        int minute = now.getHour() * 60 + now.getMinute();
        int start = window[0];
        int end = window[1];
        boolean inside = start <= end ? (minute >= start && minute < end) : (minute >= start || minute < end);
        if (!inside)
        {
            return 0L;
        }
        int remaining = minute < end ? end - minute : 24 * 60 - minute + end;
        if (remaining < 1)
        {
            remaining = 1;
        }
        return remaining * 60_000L - 1000L;
    }

    /** {@code HH:mm} 或当日分钟数 → 分钟数；越界 / 非法返回 null */
    private static Integer minuteOf(String token)
    {
        String text = token.trim();
        int colon = text.indexOf(':');
        try
        {
            if (colon > 0)
            {
                int hour = Integer.parseInt(text.substring(0, colon).trim());
                int minute = Integer.parseInt(text.substring(colon + 1).trim());
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59)
                {
                    return null;
                }
                return Integer.valueOf(hour * 60 + minute);
            }
            int value = Integer.parseInt(text);
            return value >= 0 && value <= 1440 ? Integer.valueOf(value) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
