package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.service.IMediaRootArtifactService;

/**
 * 产物根子目录清单 服务实现（P5.80）
 *
 * <p>口径（一个产物根包含全部派生资源）：{@code media_root.kind='thumbs'} 的那一行下面固定是这 7 类目录，
 * 名字与生成者见 {@link #KNOWN_DIRS}；本类只读不写，删除 / 重建由运维脚本负责。</p>
 *
 * <p>为什么要边界：{@code thumbs/p} 这类目录在真实库里已经近 2 万文件，几十万是迟早的事。一次
 * {@code Files.walk} 全量统计会把请求线程按在那里十几秒，管理端看着像卡死。因此：</p>
 *
 * <ul>
 *   <li>{@link #MAX_DEPTH} 层深（根下 1 层是分类目录，再深是分桶 / 日期目录）；</li>
 *   <li>{@link #MAX_FILES} 个文件或 {@link #MAX_MILLIS} ms 先到先停，返回 {@code partial=true} +
 *       {@code partialReason} + 已统计量（**已统计的数是真数**，只是不完整）；</li>
 *   <li>结果缓存 {@link #CACHE_TTL_MS} ms，键是 {@code media_root.id}，条目里带 path：
 *       改过 {@code media_root.path} 就必然 miss，不会拿旧目录的数骗人。</li>
 * </ul>
 *
 * <p>路径安全：只从 {@code media_root.path} 取根，扫描走 {@code Files.walkFileTree}（默认不跟随软链接），
 * 客户端没有任何拼接路径的入口。</p>
 *
 * @author qtools
 */
@Service
public class MediaRootArtifactServiceImpl implements IMediaRootArtifactService
{
    private static final Logger log = LoggerFactory.getLogger(MediaRootArtifactServiceImpl.class);

    /** 一批请求最多统计的文件数（超过就停，返回 partial） */
    public static final int MAX_FILES = 200000;

    /** 一次统计的墙上时间上限（ms） */
    public static final long MAX_MILLIS = 8000L;

    /** 最大层深：根 = 0 */
    public static final int MAX_DEPTH = 6;

    /** 结果缓存时长（ms）：管理端点开 / 收起 / 再点开不该重扫 */
    public static final long CACHE_TTL_MS = 60000L;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 已知子目录白名单：name → 中文名（顺序即展示顺序）
     *
     * <p>名字来自磁盘现状，<b>不要随手改</b>：{@code pv} 是旧预览片段（老 photo 预览），{@code pv2} 是新预览片段，
     * 两者都在，删 {@code pv} 前先看清库里还有没有引用。</p>
     */
    private static final Map<String, String> KNOWN_DIRS = new LinkedHashMap<>();

    static
    {
        KNOWN_DIRS.put("p", "图片缩略图");
        KNOWN_DIRS.put("v", "视频封面");
        KNOWN_DIRS.put("s", "精灵图");
        // pv 是「照片大图预览」（build-thumbs 只给 is_big=1 的照片生成，thumbs/pv/<hash>.jpg），
        // pv2 才是「视频预览片段」（<hash>.mp4|.webm）。历史文档里两个词容易混，这里按产物实际含义写。
        KNOWN_DIRS.put("pv", "照片大图预览");
        KNOWN_DIRS.put("pv2", "预览片段（视频）");
        KNOWN_DIRS.put("frames", "取帧缓存");
        KNOWN_DIRS.put("avcrop", "头像裁剪缓存");
    }

    /** 一次统计的结果 + 时间戳（不可变视图，读的时候只做浅拷贝） */
    private static final class CacheEntry
    {
        private final long at;

        private final String path;

        private final Map<String, Object> body;

        private CacheEntry(long at, String path, Map<String, Object> body)
        {
            this.at = at;
            this.path = path;
            this.body = body;
        }
    }

    @Autowired
    private MediaRootMapper mediaRootMapper;

    /** key = media_root.id */
    private final Map<Integer, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> artifacts(Integer rootId, boolean refresh)
    {
        if (rootId == null)
        {
            throw MediaException.badRequest("missing root id");
        }
        MediaRoot root = mediaRootMapper.selectRootById(rootId);
        if (root == null)
        {
            throw MediaException.notFound("库根不存在: id=" + rootId);
        }
        if (!"thumbs".equalsIgnoreCase(root.getKind() == null ? "" : root.getKind().trim()))
        {
            throw MediaException.badRequest("只有产物根（kind=thumbs）有子目录清单；media_root.id=" + rootId
                    + " 的 kind=" + root.getKind() + "（内容根放源文件，不放派生产物）");
        }
        String path = normalizePath(root.getPath());
        long now = System.currentTimeMillis();
        CacheEntry hit = refresh ? null : cache.get(rootId);
        if (hit != null && now - hit.at < CACHE_TTL_MS && hit.path.equals(path))
        {
            Map<String, Object> view = new LinkedHashMap<>(hit.body);
            view.put("cached", Boolean.TRUE);
            view.put("cacheAgeMs", Long.valueOf(now - hit.at));
            return view;
        }
        Map<String, Object> body = scan(root, path);
        // 根不可用（盘没挂上）不缓存：盘一挂回来下一次就该是真实数字
        if (Boolean.TRUE.equals(body.get("exists")))
        {
            cache.put(rootId, new CacheEntry(System.currentTimeMillis(), path, body));
        }
        else
        {
            cache.remove(rootId);
        }
        Map<String, Object> view = new LinkedHashMap<>(body);
        view.put("cached", Boolean.FALSE);
        view.put("cacheAgeMs", Long.valueOf(0L));
        return view;
    }

    /** 真统计：一次 walkFileTree 把根下所有文件按「第一段目录名」分桶 */
    private Map<String, Object> scan(MediaRoot root, String pathText)
    {
        long started = System.currentTimeMillis();
        Map<String, long[]> perDir = new LinkedHashMap<>();
        for (String name : KNOWN_DIRS.keySet())
        {
            perDir.put(name, new long[2]);
        }
        Map<String, long[]> unknown = new LinkedHashMap<>();
        long[] loose = new long[2];
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rootId", root.getId());
        out.put("name", root.getName());
        out.put("kind", root.getKind());
        out.put("path", pathText);

        Path base = toPath(pathText);
        if (base == null || !Files.isDirectory(base))
        {
            out.put("exists", Boolean.FALSE);
            out.put("readable", Boolean.FALSE);
            out.put("dirs", emptyDirs(false));
            out.put("unknown", new ArrayList<>());
            out.put("total", totalOf(0L, 0L));
            out.put("looseFiles", Long.valueOf(0L));
            out.put("looseBytes", Long.valueOf(0L));
            out.put("partial", Boolean.FALSE);
            out.put("partialReason", null);
            out.put("limits", limits());
            out.put("scannedAt", formatDate(started));
            out.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
            out.put("note", "根目录不存在或不是目录：" + pathText + "（盘没挂上？先挂盘再做统计）");
            return out;
        }
        out.put("exists", Boolean.TRUE);
        out.put("readable", Boolean.valueOf(Files.isReadable(base)));

        Counter counter = new Counter(base, started);
        try
        {
            Files.walkFileTree(base, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH, counter);
            for (Map.Entry<String, long[]> e : counter.perDir.entrySet())
            {
                long[] target = perDir.get(e.getKey());
                if (target != null)
                {
                    target[0] += e.getValue()[0];
                    target[1] += e.getValue()[1];
                }
            }
            for (Map.Entry<String, long[]> e : counter.unknown.entrySet())
            {
                long[] target = unknown.computeIfAbsent(e.getKey(), k -> new long[2]);
                target[0] += e.getValue()[0];
                target[1] += e.getValue()[1];
            }
        }
        catch (IOException e)
        {
            log.warn("产物根统计中断 media_root.id={} path={} : {}", root.getId(), pathText, e.getMessage());
            out.put("note", "统计中断：" + e.getMessage() + "（已统计部分仍返回）");
        }
        loose[0] = counter.looseFiles;
        loose[1] = counter.looseBytes;

        List<Map<String, Object>> dirs = new ArrayList<>();
        for (Map.Entry<String, String> e : KNOWN_DIRS.entrySet())
        {
            long[] value = perDir.get(e.getKey());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", e.getKey());
            node.put("label", e.getValue());
            Path dir = base.resolve(e.getKey());
            node.put("exists", Boolean.valueOf(Files.isDirectory(dir)));
            node.put("files", Long.valueOf(value == null ? 0L : value[0]));
            node.put("bytes", Long.valueOf(value == null ? 0L : value[1]));
            dirs.add(node);
        }
        List<Map<String, Object>> unknownList = new ArrayList<>();
        unknown.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(e ->
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", e.getKey());
            node.put("label", "未登记的子目录");
            node.put("exists", Boolean.TRUE);
            node.put("files", Long.valueOf(e.getValue()[0]));
            node.put("bytes", Long.valueOf(e.getValue()[1]));
            unknownList.add(node);
        });

        out.put("dirs", dirs);
        out.put("unknown", unknownList);
        out.put("total", totalOf(counter.files, counter.bytes));
        out.put("looseFiles", Long.valueOf(loose[0]));
        out.put("looseBytes", Long.valueOf(loose[1]));
        out.put("partial", Boolean.valueOf(counter.partial));
        out.put("partialReason", counter.partialReason);
        out.put("limits", limits());
        out.put("scannedAt", formatDate(started));
        out.put("durationMs", Long.valueOf(System.currentTimeMillis() - started));
        if (counter.partial)
        {
            out.put("note", "只统计到 " + counter.files + " 个文件就停了（" + counter.partialReason
                    + "）：已统计部分是真实数字，但不是全部；清理缓存前先看这个提示。");
        }
        else if (unknownList.isEmpty())
        {
            out.put("note", "一个产物根包含全部派生资源：下面 " + KNOWN_DIRS.size() + " 个目录就是它当前的全部分类（根下散落的文件记在 looseFiles）。");
        }
        else
        {
            out.put("note", "有 " + unknownList.size() + " 个未登记的子目录（可能是手工放的或旧版本留下的），只统计不解释。");
        }
        log.info("产物根统计 media_root.id={} path={} files={} bytes={} partial={} durationMs={}",
                root.getId(), pathText, counter.files, counter.bytes, counter.partial,
                Long.valueOf(System.currentTimeMillis() - started));
        return out;
    }

    /**
     * 有边界的遍历器：按「相对根的第一段目录名」分桶，超限即 {@code TERMINATE}
     *
     * <p>不跟随软链接（{@code FileVisitOption} 传空集），所以软链接指到盘外也不会被算进来。</p>
     */
    private static final class Counter implements FileVisitor<Path>
    {
        private final Path base;

        private final long deadline;

        private final Map<String, long[]> perDir = new LinkedHashMap<>();

        private final Map<String, long[]> unknown = new LinkedHashMap<>();

        private long files;

        private long bytes;

        private long looseFiles;

        private long looseBytes;

        private boolean partial;

        private String partialReason;

        private Counter(Path base, long startedAt)
        {
            this.base = base;
            this.deadline = startedAt + MAX_MILLIS;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        {
            if (over())
            {
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
        {
            if (over())
            {
                return FileVisitResult.TERMINATE;
            }
            if (!attrs.isRegularFile())
            {
                return FileVisitResult.CONTINUE;
            }
            long size = attrs.size();
            files++;
            bytes += size;
            Path rel = base.relativize(file);
            if (rel.getNameCount() <= 1)
            {
                looseFiles++;
                looseBytes += size;
            }
            else
            {
                String group = rel.getName(0).toString();
                Map<String, long[]> target = KNOWN_DIRS.containsKey(group) ? perDir : unknown;
                long[] value = target.computeIfAbsent(group, k -> new long[2]);
                value[0]++;
                value[1] += size;
            }
            if (files >= MAX_FILES)
            {
                partial = true;
                partialReason = "文件数已达上限 " + MAX_FILES;
                return FileVisitResult.TERMINATE;
            }
            if (System.currentTimeMillis() > deadline)
            {
                partial = true;
                partialReason = "耗时已达上限 " + MAX_MILLIS + "ms";
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
        {
            // 单个文件读不到（权限 / 占用）不该让整次统计失败：跳过并继续
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
        {
            return over() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }

        private boolean over()
        {
            if (partial)
            {
                return true;
            }
            if (System.currentTimeMillis() > deadline)
            {
                partial = true;
                partialReason = "耗时已达上限 " + MAX_MILLIS + "ms";
                return true;
            }
            return false;
        }
    }

    private static List<Map<String, Object>> emptyDirs(boolean exists)
    {
        List<Map<String, Object>> dirs = new ArrayList<>();
        for (Map.Entry<String, String> e : KNOWN_DIRS.entrySet())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", e.getKey());
            node.put("label", e.getValue());
            node.put("exists", Boolean.valueOf(exists));
            node.put("files", Long.valueOf(0L));
            node.put("bytes", Long.valueOf(0L));
            dirs.add(node);
        }
        return dirs;
    }

    private static Map<String, Object> totalOf(long files, long bytes)
    {
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("files", Long.valueOf(files));
        total.put("bytes", Long.valueOf(bytes));
        return total;
    }

    private static Map<String, Object> limits()
    {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxFiles", Integer.valueOf(MAX_FILES));
        limits.put("maxMillis", Long.valueOf(MAX_MILLIS));
        limits.put("maxDepth", Integer.valueOf(MAX_DEPTH));
        limits.put("cacheTtlMs", Long.valueOf(CACHE_TTL_MS));
        return limits;
    }

    private static String normalizePath(String path)
    {
        if (path == null)
        {
            return "";
        }
        String text = path.trim().replace('\\', '/');
        while (text.length() > 1 && text.endsWith("/") && !text.endsWith(":/"))
        {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static Path toPath(String path)
    {
        if (StringUtils.isEmpty(path))
        {
            return null;
        }
        try
        {
            return Paths.get(path);
        }
        catch (InvalidPathException e)
        {
            return null;
        }
    }

    private static String formatDate(long millis)
    {
        return DateUtils.parseDateToStr(DATE_PATTERN, new java.util.Date(millis));
    }
}
