package com.qtools.media.support;

import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 派生资源状态的判定与出参组装（纯函数，无 Spring / 无 MyBatis）
 *
 * <p>exists / stale 的口径与 build-thumbs.js 的增量判定<b>必须一致</b>，否则「列表说已生成、
 * 点生成却重建了一遍」会成为常态：</p>
 * <ul>
 *   <li>exists：thumbs/&lt;v|s|pv2&gt;/&lt;name&gt;.&lt;ext&gt; 存在且非空；</li>
 *   <li>stale：exists 且「源文件 mtime &gt; 产物 mtime」——源被替换 / 重编码后 mtime 变新，
 *       下一次 --force 之前它就一直是过期状态；</li>
 *   <li>name：content_hash 优先（内容寻址），为空才回退 videoId（老命名），
 *       与脚本的 thumbName() 同一规则。</li>
 * </ul>
 *
 * <p><b>新旧算法怎么区分</b>：stale 只比「源 vs 产物」，判不出「产物是旧取帧算法生成的」。
 * 预览片段的算法上线时刻由 {@link MediaDeriveAlgoMarker} 给出（标记文件
 * {@code <thumbs>/.derive-algo.json}，缺失时用内置默认），<b>产物 mtime 早于它即旧算法产物</b>——
 * 新算法上线后没有老算法的入口，所以「生成时间」就是新旧的判据。帧数判不了：短片在新算法下
 * 同样是 32 帧。因此每档状态除了 exists / stale 之外还有一个三态 {@code status}：</p>
 * <ul>
 *   <li>{@code missing}：产物不存在；</li>
 *   <li>{@code oldAlgo}：产物存在但 mtime 早于算法上线时刻（只有预览片段会出这个值）；</li>
 *   <li>{@code ok}：产物存在且是当前算法生成的。</li>
 * </ul>
 *
 * @author qtools
 */
public final class MediaDeriveStatusSupport
{
    /** 三态取值：产物不存在 / 旧算法产物 / 当前算法产物 */
    public static final String STATUS_MISSING = "missing";

    public static final String STATUS_OLD_ALGO = "oldAlgo";

    public static final String STATUS_OK = "ok";

    private static final DateTimeFormatter MTIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private MediaDeriveStatusSupport()
    {
    }

    /**
     * 产物文件名（不含扩展名）：content_hash 优先，空则回退 videoId
     *
     * <p>与 build-thumbs.js 的 thumbName() 同一规则：某条视频回填哈希前产出的 v/&lt;id&gt;.jpg
     * 仍在磁盘上，视图按 hash 找不到时会显示 exists=false——这是既定行为（重建一次即好）。</p>
     */
    public static String deriveName(String contentHash, String videoId)
    {
        String hash = contentHash == null ? "" : contentHash.trim();
        return hash.isEmpty() ? videoId : hash;
    }

    /**
     * 组装一行 {videoId,title,dur,thumb,sprite,preview,bytes}
     *
     * @param deriveName 产出文件名（{@link #deriveName} 的结果）
     * @param srcMtimeMs 源文件 mtime（毫秒）；srcExists=false 时忽略
     * @param srcExists  源文件是否还在盘上（missing=1 的行通常是 false）
     * @param index      目录索引（一次扫描，多次查表）
     * @param marker     算法版本标记（缺失时传 {@link MediaDeriveAlgoMarker#load(File)} 的默认结果）
     */
    public static Map<String, Object> row(String videoId, String title, BigDecimal dur, String deriveName,
            long srcMtimeMs, boolean srcExists, MediaDeriveIndex index, MediaDeriveAlgoMarker marker)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("videoId", videoId);
        row.put("title", title);
        row.put("dur", dur);
        Map<String, Object> thumb = statusOf(index, MediaDeriveIndex.KIND_THUMB, deriveName, srcMtimeMs, srcExists, marker);
        Map<String, Object> sprite = statusOf(index, MediaDeriveIndex.KIND_SPRITE, deriveName, srcMtimeMs, srcExists, marker);
        Map<String, Object> preview = statusOf(index, MediaDeriveIndex.KIND_PREVIEW, deriveName, srcMtimeMs, srcExists, marker);
        long bytes = bytesOf(thumb) + bytesOf(sprite) + bytesOf(preview);
        row.put("thumb", thumb);
        row.put("sprite", sprite);
        row.put("preview", preview);
        // 三档产物字节数之和：列表里直接显示「这条片子占了多大派生空间」
        row.put("bytes", bytes);
        return row;
    }

    /**
     * 单档状态
     *
     * <p>{@code bytes} 是内部用的中间量（拼进 row 的合计），对外只在总 bytes 里出现。
     * 预览片段的 {@code frames} 恒为 null：帧数要么 ffprobe 解一遍（一页 200 行 = 200 次子进程，
     * 不可接受），要么按文件大小猜（320x180 低码率下猜不准），两者都不如「不给」；
     * 而且<b>帧数区分不了新旧算法</b>（短片在新算法下同样是 32 帧），判新旧一律看 mtime。</p>
     *
     * <p>{@code status} 三态见类注释。缩略图 / 精灵图的算法没变过，所以它们只会是 ok / missing；
     * 字段位留着，将来若换算法，只要给 {@link #algoTracked(String)} 加一档即可。</p>
     */
    private static Map<String, Object> statusOf(MediaDeriveIndex index, String kind, String name,
            long srcMtimeMs, boolean srcExists, MediaDeriveAlgoMarker marker)
    {
        MediaDeriveIndex.Entry entry = index.find(kind, name);
        boolean exists = entry != null && entry.getBytes() > 0;
        // 源文件没了就无从判断新旧：按不 stale 处理（缺失本身由 media_video.missing 表达）
        boolean stale = exists && srcExists && srcMtimeMs > entry.getMtimeMs();
        String state = STATUS_MISSING;
        if (exists)
        {
            state = algoTracked(kind) && marker != null && marker.isOldAlgo(entry.getMtimeMs())
                    ? STATUS_OLD_ALGO : STATUS_OK;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("exists", exists);
        status.put("stale", stale);
        status.put("status", state);
        if (MediaDeriveIndex.KIND_PREVIEW.equals(kind))
        {
            // 帧数不报（见方法注释）：要精确值用 ffprobe -count_frames 单独查
            status.put("frames", null);
        }
        status.put("bytes", exists ? entry.getBytes() : 0L);
        status.put("ext", exists ? extensionOf(entry.getFileName()) : null);
        status.put("mtime", exists ? entry.getMtimeMs() : 0L);
        status.put("mtimeText", exists ? formatMtime(entry.getMtimeMs()) : null);
        return status;
    }

    /**
     * 该档是否需要参与「算法版本」判定
     *
     * <p>今天只有预览片段换过取帧算法；缩略图 / 精灵图恒为 ok / missing。
     * 将来给别的档换算法，只改这里。</p>
     */
    private static boolean algoTracked(String kind)
    {
        return MediaDeriveIndex.KIND_PREVIEW.equals(kind);
    }

    private static String formatMtime(long millis)
    {
        return millis <= 0 ? null
                : MTIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static long bytesOf(Map<String, Object> status)
    {
        Object value = status.get("bytes");
        return value instanceof Long ? (Long) value : 0L;
    }

    private static String extensionOf(String fileName)
    {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }

    /**
     * 该档是否为「需要补」：不存在 / 旧算法产物 / 已过期（管理端 kind 过滤用）
     *
     * <p>旧算法产物（oldAlgo）也算要补：它存在、甚至比源新，但内容是旧取帧算法生成的，
     * 正是「换算法后要重生成」的那一批。</p>
     */
    public static boolean needsWork(Map<String, Object> row, String kind)
    {
        Object status = row.get(kind);
        if (!(status instanceof Map))
        {
            return false;
        }
        Map<?, ?> statusMap = (Map<?, ?>) status;
        return STATUS_MISSING.equals(statusMap.get("status"))
                || STATUS_OLD_ALGO.equals(statusMap.get("status"))
                || Boolean.TRUE.equals(statusMap.get("stale"));
    }
}
