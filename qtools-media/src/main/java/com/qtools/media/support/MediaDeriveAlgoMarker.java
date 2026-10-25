package com.qtools.media.support;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 派生资源「算法版本」标记：按产物 mtime 区分新老算法
 *
 * <p>为什么要它：预览片段（pv2）的取帧算法换过一次（旧：固定 4fps × 8s = 32 帧；新：按影片时长
 * 取 dur/1 秒，夹在 32~300 帧）。<b>帧数区分不了新旧</b>——短片在新算法下同样是 32 帧；
 * 而「新算法上线之后已经没有老算法的入口」是个时间事实，因此
 * <b>产物 mtime 早于算法上线时刻 = 旧算法产物</b>，晚于它就是新的。一次目录扫描即可拿到 mtime，
 * 不需要 ffprobe。</p>
 *
 * <p>标记文件：{@code <thumbs>/.derive-algo.json}，形状
 * {@code {"pv2":{"algo":"v2","since":"2026-10-25 16:00:00"}}}。文件缺失 / 读不了 / since 解析失败
 * 一律退回内置默认值，并在接口里用 {@code algoSinceSource} 说明取值来源（marker / default），
 * 免得「标记文件没放对位置」被误读成「全库产物都是新的」。</p>
 *
 * @author qtools
 */
public final class MediaDeriveAlgoMarker
{
    /** 标记文件名（放在缩略图根下，与 v/ s/ pv2/ 同级） */
    public static final String MARKER_FILE = ".derive-algo.json";

    /** 内置默认上线时刻：新取帧算法落地的时间（标记文件缺失时用它） */
    public static final String DEFAULT_SINCE = "2026-10-25 16:00:00";

    /** 取值来源：标记文件 / 内置默认 */
    public static final String SOURCE_MARKER = "marker";

    public static final String SOURCE_DEFAULT = "default";

    private static final DateTimeFormatter TEXT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 只认 pv2 段里的 since（顶层 note 里的同一词不参与） */
    private static final Pattern PV2_SINCE = Pattern.compile(
            "\"pv2\"\\s*:\\s*\\{[^}]*?\"since\"\\s*:\\s*\"([^\"]+)\"");

    /** 兜底：任何位置的 since（标记文件被手工精简成 {"since": "..."} 也能用） */
    private static final Pattern ANY_SINCE = Pattern.compile("\"since\"\\s*:\\s*\"([^\"]+)\"");

    private final String sinceText;

    private final long sinceMillis;

    private final String source;

    private MediaDeriveAlgoMarker(String sinceText, long sinceMillis, String source)
    {
        this.sinceText = sinceText;
        this.sinceMillis = sinceMillis;
        this.source = source;
    }

    /**
     * 读标记文件；没有 / 解析不了就用内置默认
     *
     * @param thumbsDir 缩略图根（null = 直接用默认）
     */
    public static MediaDeriveAlgoMarker load(File thumbsDir)
    {
        String text = readMarker(thumbsDir);
        if (text != null)
        {
            String since = firstMatch(PV2_SINCE, text);
            if (since == null)
            {
                since = firstMatch(ANY_SINCE, text);
            }
            Long millis = parseSince(since);
            if (millis != null)
            {
                return new MediaDeriveAlgoMarker(normalize(since), millis, SOURCE_MARKER);
            }
        }
        Long fallback = parseSince(DEFAULT_SINCE);
        return new MediaDeriveAlgoMarker(DEFAULT_SINCE, fallback == null ? 0L : fallback, SOURCE_DEFAULT);
    }

    private static String readMarker(File thumbsDir)
    {
        if (thumbsDir == null)
        {
            return null;
        }
        File marker = new File(thumbsDir, MARKER_FILE);
        if (!marker.isFile())
        {
            return null;
        }
        try
        {
            return new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            // 标记文件坏掉不该让整个列表 500：退回内置默认，来源会标成 default
            return null;
        }
    }

    private static String firstMatch(Pattern pattern, String text)
    {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** "yyyy-MM-dd HH:mm:ss"（也接受 ISO 的 T 分隔与毫秒尾巴）；解析失败返回 null */
    private static Long parseSince(String text)
    {
        String value = normalize(text);
        if (value == null)
        {
            return null;
        }
        try
        {
            return LocalDateTime.parse(value, TEXT_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String normalize(String text)
    {
        if (text == null)
        {
            return null;
        }
        String value = text.trim().replace('T', ' ');
        int dot = value.indexOf('.');
        if (dot > 0)
        {
            value = value.substring(0, dot);
        }
        return value.isEmpty() ? null : value;
    }

    /** 算法上线时刻的文本形式（接口回 {@code algoSince}） */
    public String getSinceText()
    {
        return sinceText;
    }

    /** 算法上线时刻（毫秒）：产物 mtime 小于它即旧算法产物 */
    public long getSinceMillis()
    {
        return sinceMillis;
    }

    /** marker / default（接口回 {@code algoSinceSource}） */
    public String getSource()
    {
        return source;
    }

    /**
     * 判定一个产物是不是旧算法产物
     *
     * @param mtimeMs 产物 mtime；文件不存在传 0（调用方先判存在）
     * @return true = 早于算法上线时刻
     */
    public boolean isOldAlgo(long mtimeMs)
    {
        return sinceMillis > 0 && mtimeMs > 0 && mtimeMs < sinceMillis;
    }
}
