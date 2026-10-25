package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.service.IMediaMaintenanceService;

/**
 * 媒体库日常维护 服务实现（多库根 + 导出产物）
 *
 * <p>设计口径：</p>
 * <ol>
 *   <li><b>根不猜</b>：{@code path} 必须当前就存在且是目录才让写库——「换盘前先把根配好」是运维动作，
 *       写进库再抱怨「根不可用」只会把问题推到用户端；</li>
 *   <li><b>删除先看引用</b>：{@code media_video} / {@code media_photo} 的 {@code root_id} 还有行时 400 并
 *       返回引用数，绝不留下悬空引用；</li>
 *   <li><b>导出只读一个根</b>：目录名与文件名都过白名单正则，解析后用 {@code toRealPath()} 复核，
 *       软链接也逃不出去。</li>
 * </ol>
 *
 * @author qtools
 */
@Service
public class MediaMaintenanceServiceImpl implements IMediaMaintenanceService
{
    private static final Logger log = LoggerFactory.getLogger(MediaMaintenanceServiceImpl.class);

    /** 导出目录名：yyyyMMdd-HHmmss（与 MediaScanServiceImpl 生成的一致） */
    private static final Pattern EXPORT_DIR = Pattern.compile("^[0-9]{8}-[0-9]{6}$");

    /** 允许下载的文件名：字母数字点线 + .sql / .gz / .json / .zip（media.sql.gz 也匹配） */
    private static final Pattern EXPORT_FILE = Pattern.compile("^[A-Za-z0-9._-]+\\.(sql|gz|json|zip)$");

    /** 导出历史只回最近 10 次 */
    private static final int EXPORT_LIST_LIMIT = 10;

    /** 库根 kind 白名单 */
    private static final List<String> ROOT_KINDS = List.of("root", "thumbs", "trash");

    private static final int NAME_MAX = 64;

    private static final int NOTE_MAX = 255;

    private static final int PATH_MAX = 512;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private MediaRootMapper mediaRootMapper;

    @Autowired
    private MediaProperties mediaProperties;

    // ------------------------------------------------------------------ 多库根

    @Override
    public List<Map<String, Object>> listRoots()
    {
        List<MediaRoot> roots = mediaRootMapper.selectRootList();
        // 一次 group by 拿条数：根通常只有 2~3 行，但每行都要 count 的话就是 N 次全表扫
        long[] nullVideos = new long[1];
        long[] nullPhotos = new long[1];
        Map<Integer, Long> videoCounts = countByRoot(mediaRootMapper.countVideosByRoot(), nullVideos);
        Map<Integer, Long> photoCounts = countByRoot(mediaRootMapper.countPhotosByRoot(), nullPhotos);
        Integer defaultRootId = mediaRootMapper.selectDefaultRootId();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaRoot root : roots)
        {
            Path path = toPath(root.getPath());
            boolean exists = path != null && Files.exists(path);
            long videos = countOf(videoCounts, root.getId());
            long photos = countOf(photoCounts, root.getId());
            if (root.getId() != null && root.getId().equals(defaultRootId) && "root".equalsIgnoreCase(root.getKind()))
            {
                // root_id 为 NULL 的存量行一律走配置 rootPath，也就是这个默认根
                videos += nullVideos[0];
                photos += nullPhotos[0];
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", root.getId());
            node.put("name", root.getName());
            node.put("path", normalizePath(root.getPath()));
            node.put("kind", root.getKind());
            node.put("note", root.getNote());
            node.put("exists", exists);
            node.put("readable", path != null && Files.isReadable(path));
            node.put("videoCount", videos);
            node.put("photoCount", photos);
            node.put("isDefault", root.getId() != null && root.getId().equals(defaultRootId));
            out.add(node);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createRoot(String name, String path, String kind, String note)
    {
        String nameText = requireName(name);
        String pathText = requireDirectory(path);
        String kindText = normalizeKind(kind, "root");
        String noteText = normalizeNote(note);
        MediaRoot duplicated = mediaRootMapper.selectRootByPath(pathText);
        if (duplicated != null)
        {
            throw MediaException.conflict("库根路径已存在：id=" + duplicated.getId() + " name=" + duplicated.getName()
                    + " path=" + pathText);
        }
        MediaRoot root = new MediaRoot();
        root.setName(nameText);
        root.setPath(pathText);
        root.setKind(kindText);
        root.setNote(noteText);
        mediaRootMapper.insertRoot(root);
        log.info("新增库根 id={} name={} kind={} path={}", root.getId(), nameText, kindText, pathText);
        Map<String, Object> view = rootView(mediaRootMapper.selectRootById(root.getId()));
        view.put("created", true);
        view.put("hint", "新根还没有媒体行：跑一次 mode=scan（带 --root-id=" + root.getId()
                + "）或 --root= 指向它，才能让 media_video/media_photo 的 root_id 落到这一行");
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateRoot(Integer id, String name, String path, String note)
    {
        MediaRoot current = mediaRootMapper.selectRootById(id);
        if (current == null)
        {
            throw MediaException.notFound("库根不存在: id=" + id);
        }
        MediaRoot patch = new MediaRoot();
        patch.setId(id);
        boolean changed = false;
        String nameText = StringUtils.isEmpty(name) ? null : requireName(name);
        if (nameText != null)
        {
            patch.setName(nameText);
            changed = true;
        }
        boolean pathChanged = false;
        if (!StringUtils.isEmpty(path))
        {
            String pathText = requireDirectory(path);
            MediaRoot duplicated = mediaRootMapper.selectRootByPath(pathText);
            if (duplicated != null && !duplicated.getId().equals(id))
            {
                throw MediaException.conflict("库根路径已存在：id=" + duplicated.getId() + " name="
                        + duplicated.getName() + " path=" + pathText);
            }
            pathChanged = !pathText.equals(normalizePath(current.getPath()));
            patch.setPath(pathText);
            changed = true;
        }
        if (note != null)
        {
            patch.setNote(normalizeNote(note));
            changed = true;
        }
        if (!changed)
        {
            throw MediaException.badRequest("没有要修改的字段（name / path / note 至少给一个）");
        }
        mediaRootMapper.updateRoot(patch);
        log.info("修改库根 id={} name={} pathChanged={}", id, nameText, pathChanged);
        Map<String, Object> view = rootView(mediaRootMapper.selectRootById(id));
        view.put("updated", true);
        view.put("pathChanged", pathChanged);
        if (pathChanged)
        {
            // 改路径不会自动重扫：rel_path 仍按旧根算，root_id 也不会自己搬过去
            view.put("hint", "已改 path 但**不会**自动重扫：rel_path / root_id 仍指向旧布局，"
                    + "需要再跑一次 mode=scan（或 backfill-hash）重新对齐");
        }
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteRoot(Integer id)
    {
        MediaRoot current = mediaRootMapper.selectRootById(id);
        if (current == null)
        {
            throw MediaException.notFound("库根不存在: id=" + id);
        }
        int videos = mediaRootMapper.countVideoByRootId(id);
        int photos = mediaRootMapper.countPhotoByRootId(id);
        if (videos + photos > 0)
        {
            throw MediaException.badRequest("库根仍被引用：root_id=" + id + " 上还有 videos=" + videos
                    + " photos=" + photos + "（先把这些行的 root_id 迁到别的根或置 NULL 再删）");
        }
        boolean wasDefault = id.equals(mediaRootMapper.selectDefaultRootId());
        mediaRootMapper.deleteRootById(id);
        log.info("删除库根 id={} name={} wasDefault={}", id, current.getName(), wasDefault);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deleted", 1);
        view.put("id", id);
        view.put("name", current.getName());
        view.put("path", normalizePath(current.getPath()));
        if (wasDefault)
        {
            view.put("warning", "删掉的是默认媒体根（kind='root' 的第一行）：扫描器 --root-id 缺省值会顺延到下一条");
        }
        return view;
    }

    // ------------------------------------------------------------------ 导出产物

    @Override
    public List<Map<String, Object>> exportList()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        Path root = exportRoot();
        if (!Files.isDirectory(root))
        {
            return out;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root))
        {
            stream.filter(Files::isDirectory)
                    .filter(item -> EXPORT_DIR.matcher(item.getFileName().toString()).matches())
                    // toRealPath 复核：目录联接 / 软链接指向导出根之外时，清单里也不列它
                    .filter(item -> insideRoot(root, item))
                    .forEach(dirs::add);
        }
        catch (IOException e)
        {
            throw new MediaException(500, "读取导出根失败: " + root + "（" + e.getMessage() + "）");
        }
        // 目录名是 yyyyMMdd-HHmmss：字典序倒排即时间倒排
        dirs.sort(Comparator.comparing((Path item) -> item.getFileName().toString()).reversed());
        for (int i = 0; i < dirs.size() && i < EXPORT_LIST_LIMIT; i++)
        {
            out.add(exportDirView(dirs.get(i)));
        }
        return out;
    }

    @Override
    public Path resolveExportFile(String dir, String file)
    {
        String dirName = dir == null ? "" : dir.trim();
        String fileName = file == null ? "" : file.trim();
        if (!EXPORT_DIR.matcher(dirName).matches())
        {
            throw MediaException.badRequest("dir 必须是 yyyyMMdd-HHmmss");
        }
        if (!EXPORT_FILE.matcher(fileName).matches())
        {
            throw MediaException.badRequest("file 必须形如 <名字>.(sql|gz|json|zip)（只允许字母数字点线与下划线）");
        }
        Path root = exportRoot();
        Path target = root.resolve(dirName).resolve(fileName).normalize();
        if (!target.startsWith(root))
        {
            // 正则里没有 / 和 ..，正常走不到这里：留作解析规则变化时的兜底
            throw MediaException.forbidden();
        }
        try
        {
            // toRealPath 复核：导出目录里如果有人放了指向根外的软链接，这一步把它拦下来
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot) || !Files.isRegularFile(realTarget))
            {
                throw MediaException.forbidden();
            }
            return realTarget;
        }
        catch (IOException e)
        {
            throw MediaException.notFound("导出文件不存在: " + dirName + "/" + fileName);
        }
    }

    // ------------------------------------------------------------------ 内部工具

    /** 解析真实路径后判断是否仍在导出根内（拦目录联接 / 软链接） */
    private static boolean insideRoot(Path root, Path candidate)
    {
        try
        {
            return candidate.toRealPath().startsWith(root.toRealPath());
        }
        catch (IOException e)
        {
            return false;
        }
    }

    /** 导出根：{@code qtools.media.exportPath}（缺省 {@code <qtools.home>/data/media/export}） */
    private Path exportRoot()
    {
        String configured = mediaProperties.getExportPath();
        if (StringUtils.isEmpty(configured))
        {
            throw new MediaException(500, "未配置 qtools.media.exportPath（或 qtools.home），无法定位导出目录");
        }
        try
        {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        catch (InvalidPathException e)
        {
            throw new MediaException(500, "qtools.media.exportPath 非法: " + configured);
        }
    }

    /** 一个导出目录的清单：{dir,createdAt,fileCount,bytes,files:[{name,bytes,isDir,modifiedAt,url}]} */
    private Map<String, Object> exportDirView(Path dir)
    {
        String dirName = dir.getFileName().toString();
        List<Map<String, Object>> files = new ArrayList<>();
        long bytes = 0L;
        int fileCount = 0;
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir))
        {
            stream.forEach(entries::add);
        }
        catch (IOException e)
        {
            throw new MediaException(500, "读取导出目录失败: " + dir + "（" + e.getMessage() + "）");
        }
        entries.sort(Comparator.comparing(item -> item.getFileName().toString()));
        for (Path entry : entries)
        {
            try
            {
                Map<String, Object> node = new LinkedHashMap<>();
                if (Files.isDirectory(entry))
                {
                    long size = treeBytes(entry);
                    node.put("name", entry.getFileName() + "/");
                    node.put("bytes", size);
                    node.put("isDir", true);
                    bytes += size;
                }
                else
                {
                    long size = Files.size(entry);
                    node.put("name", entry.getFileName().toString());
                    node.put("bytes", size);
                    node.put("isDir", false);
                    node.put("modifiedAt", formatDate(new Date(Files.getLastModifiedTime(entry).toMillis())));
                    node.put("url", "/api/media/admin/export/download?dir=" + dirName + "&file="
                            + entry.getFileName());
                    bytes += size;
                    fileCount++;
                }
                files.add(node);
            }
            catch (IOException e)
            {
                log.warn("导出目录条目读取失败 dir={} entry={}", dir, entry, e);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dir", dirName);
        view.put("fileCount", fileCount);
        view.put("bytes", bytes);
        view.put("files", files);
        return view;
    }

    /** 目录总字节（导出包里只有 thumbs/ 这种可选子目录，深度有限） */
    private static long treeBytes(Path dir)
    {
        try (Stream<Path> stream = Files.walk(dir))
        {
            long sum = 0L;
            for (Path item : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator)
            {
                try
                {
                    sum += Files.size(item);
                }
                catch (IOException ignored)
                {
                    // 单个文件读不到大小不影响清单：跳过即可
                }
            }
            return sum;
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    /** group by 结果 → {rootId: cnt}；root_id 为 NULL 的那组单独放进 nullBucket[0] */
    private static Map<Integer, Long> countByRoot(List<Map<String, Object>> rows, long[] nullBucket)
    {
        Map<Integer, Long> out = new HashMap<>();
        if (rows == null)
        {
            return out;
        }
        for (Map<String, Object> row : rows)
        {
            Object rootId = pick(row, "rootId", "root_id", "ROOTID");
            Object count = pick(row, "cnt", "CNT", "count");
            long value = count == null ? 0L : ((Number) count).longValue();
            if (rootId == null)
            {
                nullBucket[0] = value;
                continue;
            }
            out.put(Integer.valueOf(((Number) rootId).intValue()), Long.valueOf(value));
        }
        return out;
    }

    /** Map 取列：列别名大小写由驱动决定，这里按候选名逐个试 */
    private static Object pick(Map<String, Object> row, String... keys)
    {
        for (String key : keys)
        {
            if (row.containsKey(key))
            {
                return row.get(key);
            }
        }
        for (Map.Entry<String, Object> entry : row.entrySet())
        {
            for (String key : keys)
            {
                if (key.equalsIgnoreCase(entry.getKey()))
                {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static long countOf(Map<Integer, Long> counts, Integer id)
    {
        Long value = id == null ? null : counts.get(id);
        return value == null ? 0L : value.longValue();
    }

    /** 单个库根的视图（写接口的返回体，字段与 GET /roots 的行一致） */
    private Map<String, Object> rootView(MediaRoot root)
    {
        Map<String, Object> view = new LinkedHashMap<>();
        if (root == null)
        {
            return view;
        }
        Path path = toPath(root.getPath());
        view.put("id", root.getId());
        view.put("name", root.getName());
        view.put("path", normalizePath(root.getPath()));
        view.put("kind", root.getKind());
        view.put("note", root.getNote());
        view.put("exists", path != null && Files.exists(path));
        view.put("readable", path != null && Files.isReadable(path));
        view.put("createdAt", formatDate(root.getCreatedAt()));
        return view;
    }

    /** name：必填、1~64 字符 */
    private static String requireName(String name)
    {
        String text = name == null ? "" : name.trim();
        if (text.isEmpty())
        {
            throw MediaException.badRequest("missing name");
        }
        if (text.length() > NAME_MAX)
        {
            throw MediaException.badRequest("name 过长（最多 " + NAME_MAX + " 字符）");
        }
        return text;
    }

    /** path：必填、存在且是目录；统一成正斜杠（与 application.yml 的写法一致） */
    private static String requireDirectory(String path)
    {
        String text = normalizePath(path);
        if (text.isEmpty())
        {
            throw MediaException.badRequest("missing path");
        }
        if (text.length() > PATH_MAX)
        {
            throw MediaException.badRequest("path 过长（最多 " + PATH_MAX + " 字符）");
        }
        Path directory = toPath(text);
        if (directory == null)
        {
            throw MediaException.badRequest("path 非法: " + text);
        }
        if (!Files.exists(directory))
        {
            throw MediaException.badRequest("path 不存在: " + text + "（先把盘挂上 / 建好目录再配根）");
        }
        if (!Files.isDirectory(directory))
        {
            throw MediaException.badRequest("path 不是目录: " + text);
        }
        return text;
    }

    private static String normalizeKind(String kind, String fallback)
    {
        String text = kind == null ? "" : kind.trim().toLowerCase();
        if (text.isEmpty())
        {
            return fallback;
        }
        if (!ROOT_KINDS.contains(text))
        {
            throw MediaException.badRequest("kind 只接受 " + String.join(" / ", ROOT_KINDS) + "（收到：" + kind + "）");
        }
        return text;
    }

    private static String normalizeNote(String note)
    {
        String text = note == null ? "" : note.trim();
        if (text.length() > NOTE_MAX)
        {
            throw MediaException.badRequest("note 过长（最多 " + NOTE_MAX + " 字符）");
        }
        return text;
    }

    /** 空 → 空串；正斜杠归一 + 去掉尾部斜杠（{@code E:/} 这种盘根保留斜杠） */
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

    private static String formatDate(Date date)
    {
        return date == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, date);
    }
}
