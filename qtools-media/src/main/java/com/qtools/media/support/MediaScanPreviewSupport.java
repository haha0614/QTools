package com.qtools.media.support;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 扫描预演（dry-run）的「忽略类型统计」辅助（P5.97）
 *
 * <p>为什么需要它：{@code scan-media.js --dry-run} 已经在<b>不连库、不写库</b>的前提下报出
 * {@code videos=/albums=/photos=} 三个数字（那三个数字是唯一权威值，本类不重复算），但它不报
 * 「哪些文件被忽略了」——用户填完路径最想知道恰恰是「我这一堆 mkv / txt / 压缩包到底进不进库」。
 * 所以这里只补一件事：按扩展名统计目录里<b>不会入库</b>的文件。</p>
 *
 * <p>扩展名清单必须与 {@code doc/media-import/scan-media.js} 的 {@code VIDEO_EXT} / {@code PHOTO_EXT}
 * 完全一致（脚本是唯一真源）：脚本改了这里也要改，否则预演的数字与真实扫描对不上。</p>
 *
 * <p>扫盘带边界（与 {@code MediaRootArtifactServiceImpl} 同口径）：最多 {@link #MAX_FILES} 个文件 /
 * {@link #MAX_MILLIS} 毫秒 / {@link #MAX_SUBDIRS} 个子目录；超限就停并置 {@code truncated=true}，
 * 已统计的部分仍是真数——预演是给人看的，宁可少算也不要把管理端拖死。</p>
 *
 * @author qtools
 */
public final class MediaScanPreviewSupport
{
    /** 与 scan-media.js 的 VIDEO_EXT 一致 */
    public static final Set<String> VIDEO_EXT = Set.of(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".avi");

    /** 与 scan-media.js 的 PHOTO_EXT 一致 */
    public static final Set<String> PHOTO_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    /** 单次统计的文件上限（20 万：一个 2000 部片子的库） */
    private static final int MAX_FILES = 200000;

    /** 单次统计的时间上限（毫秒） */
    private static final long MAX_MILLIS = 6000L;

    /** 子目录数量上限 */
    private static final int MAX_SUBDIRS = 200000;

    /** 忽略类型最多列几类（其余只进总量） */
    private static final int TOP_IGNORED = 8;

    private MediaScanPreviewSupport()
    {
    }

    /** 一次目录统计的结果 */
    public static final class Walk
    {
        /** 看到的文件总数（= 视频 + 图片 + 忽略） */
        public long files;

        public long videos;

        public long photos;

        public long ignored;

        public long bytes;

        public long videoBytes;

        public long photoBytes;

        public long dirs;

        /** 达到边界提前收工：数字是「至少这么多」 */
        public boolean truncated;

        /** 截断原因（给页面显示一句话） */
        public String truncatedReason = "";

        /** 被忽略的扩展名 → 文件数（按数量降序，最多 {@link #TOP_IGNORED} 项） */
        public final Map<String, Long> ignoredByExt = new LinkedHashMap<>();
    }

    /**
     * 统计一个目录里的文件（只 stat，不读内容）
     *
     * @param root 已确认为存在的目录
     * @return 统计结果；目录读不动时抛 IOException（调用方转成 400）
     */
    public static Walk walk(Path root) throws IOException
    {
        Walk walk = new Walk();
        long begin = System.currentTimeMillis();
        Map<String, Long> ignoredAll = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            {
                walk.dirs += 1;
                if (walk.dirs > MAX_SUBDIRS || System.currentTimeMillis() - begin > MAX_MILLIS)
                {
                    return stop(walk, "子目录超过 " + MAX_SUBDIRS + " 个或统计超过 " + MAX_MILLIS + "ms");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            {
                if (walk.files >= MAX_FILES)
                {
                    return stop(walk, "文件数超过 " + MAX_FILES);
                }
                if (System.currentTimeMillis() - begin > MAX_MILLIS)
                {
                    return stop(walk, "统计超过 " + MAX_MILLIS + "ms");
                }
                if (!attrs.isRegularFile())
                {
                    return FileVisitResult.CONTINUE;
                }
                walk.files += 1;
                long size = Math.max(0L, attrs.size());
                walk.bytes += size;
                String ext = extensionOf(file.getFileName() == null ? "" : file.getFileName().toString());
                if (VIDEO_EXT.contains(ext))
                {
                    walk.videos += 1;
                    walk.videoBytes += size;
                }
                else if (PHOTO_EXT.contains(ext))
                {
                    walk.photos += 1;
                    walk.photoBytes += size;
                }
                else
                {
                    walk.ignored += 1;
                    ignoredAll.merge(ext, 1L, Long::sum);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc)
            {
                // 单个文件 stat 失败（权限 / 被占用）只跳过：预演不该因为一个坏文件整页报错
                return FileVisitResult.CONTINUE;
            }
        });
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(ignoredAll.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Long>> comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        for (int i = 0; i < sorted.size() && i < TOP_IGNORED; i++)
        {
            walk.ignoredByExt.put(sorted.get(i).getKey(), sorted.get(i).getValue());
        }
        return walk;
    }

    private static FileVisitResult stop(Walk walk, String reason)
    {
        if (!walk.truncated)
        {
            walk.truncated = true;
            walk.truncatedReason = reason;
        }
        return FileVisitResult.TERMINATE;
    }

    /** 扩展名（小写带点）；无扩展名给空串（页面上显示成「无扩展名」） */
    public static String extensionOf(String name)
    {
        String text = name == null ? "" : name.trim();
        int dot = text.lastIndexOf('.');
        if (dot <= 0 || dot == text.length() - 1)
        {
            return "";
        }
        return text.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * 粗估耗时（秒）：{@code 走盘 × 1.5 + 文件数 × 20ms + 视频 × 0.8s + 图片 × 0.12s}
     *
     * <p>故意写成「明显是粗估」的线性式子并原样告诉前端：真实耗时取决于盘速、是否要解码取帧
     * （精灵图 / 预览是全片解码，比封面慢两个数量级）。页面文案必须带「约」。预演<b>不</b>承诺时间，
     * 只给一个量级，避免把估算当成 SLA。</p>
     */
    public static long estimateSeconds(long walkMillis, long videos, long photos, long files)
    {
        double seconds = walkMillis / 1000.0 * 1.5d + files * 0.02d + videos * 0.8d + photos * 0.12d;
        return Math.max(1L, Math.round(seconds));
    }
}
