package com.qtools.media.support;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 派生资源目录索引：thumbs/{v,s,pv2} 的<b>一次</b>目录扫描结果
 *
 * <p>管理端「派生资源状态」列表要一次判断几千行的 thumb / sprite / preview 是否存在、
 * 是否过期。逐行 {@code Files.exists} 会变成 N×3 次系统调用；这里改成<b>每个目录只列一次</b>，
 * 把「文件名去扩展名 → mtime / 字节数」读进内存 Map，之后逐行只是 Map 查表。</p>
 *
 * <p>三个目录的命名约定与 build-thumbs.js 一致（内容寻址，content_hash 为空才回退 videoId）：</p>
 * <ul>
 *   <li>{@code v/<name>.jpg}：视频封面；</li>
 *   <li>{@code s/<name>.jpg}：精灵图；</li>
 *   <li>{@code pv2/<name>.mp4}（或 .webm）：预览片段。</li>
 * </ul>
 *
 * <p>只读：不创建目录、不删除文件；目录不存在时按「该档一个产物都没有」处理，不抛异常。</p>
 *
 * @author qtools
 */
public final class MediaDeriveIndex
{
    /** 派生档位名（对外契约里的 key，也是目录名映射的索引） */
    public static final String KIND_THUMB = "thumb";

    public static final String KIND_SPRITE = "sprite";

    public static final String KIND_PREVIEW = "preview";

    /** 档位 → thumbs 下的子目录名 */
    private static final Map<String, String> SUB_DIRS = Map.of(
            KIND_THUMB, "v", KIND_SPRITE, "s", KIND_PREVIEW, "pv2");

    /** 一个产物文件：mtime 与字节数（mtime 判过期，字节数给列表里的体积列） */
    public static final class Entry
    {
        private final long mtimeMs;

        private final long bytes;

        private final String fileName;

        Entry(long mtimeMs, long bytes, String fileName)
        {
            this.mtimeMs = mtimeMs;
            this.bytes = bytes;
            this.fileName = fileName;
        }

        public long getMtimeMs()
        {
            return mtimeMs;
        }

        public long getBytes()
        {
            return bytes;
        }

        public String getFileName()
        {
            return fileName;
        }
    }

    /** 档位 → (文件名去扩展名 → 产物)；文件名统一小写做键，Windows / Linux 产物同名不同大小写也能命中 */
    private final Map<String, Map<String, Entry>> byKind;

    private MediaDeriveIndex(Map<String, Map<String, Entry>> byKind)
    {
        this.byKind = byKind;
    }

    /**
     * 扫一遍 thumbs/{v,s,pv2}
     *
     * @param thumbsDir 缩略图根（为空或不存在都返回空索引，接口照常出结果）
     */
    public static MediaDeriveIndex scan(File thumbsDir)
    {
        Map<String, Map<String, Entry>> byKind = new HashMap<>();
        for (Map.Entry<String, String> pair : SUB_DIRS.entrySet())
        {
            byKind.put(pair.getKey(), scanDir(thumbsDir, pair.getValue()));
        }
        return new MediaDeriveIndex(byKind);
    }

    private static Map<String, Entry> scanDir(File thumbsDir, String sub)
    {
        Map<String, Entry> entries = new HashMap<>();
        if (thumbsDir == null)
        {
            return entries;
        }
        File dir = new File(thumbsDir, sub);
        File[] files = dir.listFiles(File::isFile);
        if (files == null)
        {
            return entries;
        }
        for (File file : files)
        {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            if (stem.isEmpty())
            {
                continue;
            }
            String key = stem.toLowerCase(Locale.ROOT);
            long mtime = file.lastModified();
            Entry previous = entries.get(key);
            if (previous == null || previous.getMtimeMs() < mtime)
            {
                // 同一个 stem 两种扩展名（.mp4 / .webm）共存时取 mtime 较新的那个
                entries.put(key, new Entry(mtime, file.length(), name));
            }
        }
        return entries;
    }

    /** 某档某名字的产物；不存在返回 null */
    public Entry find(String kind, String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        Map<String, Entry> entries = byKind.get(kind);
        return entries == null ? null : entries.get(name.toLowerCase(Locale.ROOT));
    }

    /** 该档产物的目录里一共有多少个文件（给日志 / 自检用） */
    public int sizeOf(String kind)
    {
        Map<String, Entry> entries = byKind.get(kind);
        return entries == null ? 0 : entries.size();
    }
}
