package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaFileService;

/**
 * 媒体文件定位与内容处理 服务实现
 *
 * <p>安全规则（与 Node 版 server.js 的 safeMedia 对齐）：</p>
 * <ol>
 *   <li>拒绝空/NUL/绝对路径/盘符/UNC/前导分隔符；</li>
 *   <li>resolve+normalize 后必须仍在媒体根内；</li>
 *   <li>realpath 之后必须仍在媒体根内（防符号链接逃逸）；</li>
 *   <li>禁止落在站内目录 {@code _媒体站}（站点自身文件不对外暴露）。</li>
 * </ol>
 *
 * @author qtools
 */
@Service
public class MediaFileServiceImpl implements IMediaFileService
{
    /** 站内目录名，禁止通过 /file 暴露 */
    private static final String STATION_DIR = "_媒体站";

    /** 回收站目录名（位于站内目录下，因此永远不会成为软删目标） */
    private static final String TRASH_DIR = "trash";

    /** 回收站允许的分类，防止 kind 被当成路径片段注入 */
    private static final Set<String> TRASH_KINDS = Set.of("video", "album", "photo");

    private static final String THUMB_VIDEO_DIR = "v";

    private static final String THUMB_PHOTO_DIR = "p";

    private static final String PREVIEW_PHOTO_DIR = "pv";

    private static final String JPG_SUFFIX = ".jpg";

    /** 视频 id / 文件名片段：只允许安全字符，杜绝路径分隔符与 . / .. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 相册 id：a001 这类短 id */
    private static final Pattern SAFE_ALBUM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,16}$");

    /** 册内序号：纯数字，最多 6 位 */
    private static final Pattern SAFE_INDEX = Pattern.compile("^\\d{1,6}$");

    /** 内容哈希文件名片段：十六进制（content_hash 取 sha1 前 16 位）；脏数据不进文件路径 */
    private static final Pattern SAFE_CONTENT_HASH = Pattern.compile("^[0-9a-fA-F]{6,64}$");

    /** 缩略图文件名固定 4 位序号（0000.jpg），不足补零 */
    private static final int THUMB_INDEX_WIDTH = 4;

    private static final Map<String, String> MIME = new LinkedHashMap<>();

    static
    {
        MIME.put(".mp4", "video/mp4");
        MIME.put(".m4v", "video/mp4");
        MIME.put(".webm", "video/webm");
        MIME.put(".mkv", "video/x-matroska");
        MIME.put(".mov", "video/quicktime");
        MIME.put(".avi", "video/x-msvideo");
        MIME.put(".flv", "video/x-flv");
        MIME.put(".wmv", "video/x-ms-wmv");
        MIME.put(".ts", "video/mp2t");
        MIME.put(".jpg", "image/jpeg");
        MIME.put(".jpeg", "image/jpeg");
        MIME.put(".png", "image/png");
        MIME.put(".webp", "image/webp");
        MIME.put(".gif", "image/gif");
        MIME.put(".avif", "image/avif");
        MIME.put(".bmp", "image/bmp");
        MIME.put(".ico", "image/x-icon");
        MIME.put(".svg", "image/svg+xml");
        MIME.put(".vtt", "text/vtt; charset=utf-8");
        MIME.put(".srt", "application/x-subrip; charset=utf-8");
        MIME.put(".txt", "text/plain; charset=utf-8");
        MIME.put(".md", "text/plain; charset=utf-8");
        MIME.put(".html", "text/html; charset=utf-8");
        MIME.put(".htm", "text/html; charset=utf-8");
        MIME.put(".js", "text/javascript; charset=utf-8");
        MIME.put(".mjs", "text/javascript; charset=utf-8");
        MIME.put(".css", "text/css; charset=utf-8");
        MIME.put(".json", "application/json; charset=utf-8");
        MIME.put(".map", "application/json; charset=utf-8");
        MIME.put(".woff2", "font/woff2");
        MIME.put(".mp3", "audio/mpeg");
        MIME.put(".m4a", "audio/mp4");
        MIME.put(".aac", "audio/aac");
        MIME.put(".wav", "audio/wav");
        MIME.put(".flac", "audio/flac");
        MIME.put(".pdf", "application/pdf");
    }

    private static final String DEFAULT_MIME = "application/octet-stream";

    private static final String VTT_HEADER = "WEBVTT";

    @Autowired
    private MediaProperties mediaProperties;

    /** 视频内容哈希来源：只有「按内容寻址取封面」需要读库，读不到就回退老命名 */
    @Autowired
    private MediaVideoMapper mediaVideoMapper;

    /** 多库根来源：只有显式 root_id 或默认根找不到文件时才读库 */
    @Autowired
    private MediaRootMapper mediaRootMapper;

    /** 已解析的媒体根 realpath 及其对应的配置值（配置变更后自动重算） */
    private volatile String rootConfig;

    private volatile Path rootReal;

    /** 已解析的缩略图根 realpath */
    private volatile String thumbsConfig;

    private volatile Path thumbsReal;

    @Override
    public Path resolveMedia(String rel, Integer rootId)
    {
        if (StringUtils.isEmpty(rel))
        {
            throw MediaException.badRequest("missing p");
        }
        // 路径语法与根无关：先判一次，绝对路径/UNC/盘符/.. 逃逸一律 403，
        // 不给多根兜底任何「换个根再试试」的机会
        checkRelSyntax(rel);
        Path root = rootDir(rootId);
        if (rootId != null)
        {
            return fileIn(root, rel);
        }
        try
        {
            return fileIn(root, rel);
        }
        catch (MediaException e)
        {
            if (e.getStatus() != 404)
            {
                throw e;
            }
        }
        // root_id 为 NULL 的存量行 / 前端未传 root_id：默认根没有就试其它媒体根
        for (Path other : otherRoots(root))
        {
            try
            {
                return fileIn(other, rel);
            }
            catch (MediaException e)
            {
                // 这个根里没有或越界：换下一个；最终仍回 404，不泄漏其它根的情况
            }
        }
        throw MediaException.notFound();
    }

    @Override
    public Path resolveMediaDir(String rel, Integer rootId)
    {
        if (StringUtils.isEmpty(rel))
        {
            throw MediaException.badRequest("missing dir");
        }
        checkRelSyntax(rel);
        Path root = rootDir(rootId);
        if (rootId != null)
        {
            return dirIn(root, rel);
        }
        try
        {
            return dirIn(root, rel);
        }
        catch (MediaException e)
        {
            if (e.getStatus() != 404)
            {
                throw e;
            }
        }
        for (Path other : otherRoots(root))
        {
            try
            {
                return dirIn(other, rel);
            }
            catch (MediaException e)
            {
                // 同上：换下一个根
            }
        }
        throw MediaException.notFound();
    }

    @Override
    public Path resolveWriteTarget(String rel, Integer rootId)
    {
        if (StringUtils.isEmpty(rel))
        {
            throw MediaException.badRequest("missing path");
        }
        Path root = rootDir(rootId);
        Path target = resolveInside(root, rel);
        if (target.startsWith(stationDir(root)))
        {
            throw MediaException.forbidden();
        }
        requireRealInside(root, target);
        return target;
    }

    @Override
    public Path mediaRoot(Integer rootId)
    {
        return rootDir(rootId);
    }

    @Override
    public Path trashRoot()
    {
        String configured = mediaProperties.getTrashPath();
        Path root = rootDir();
        Path trash = StringUtils.isEmpty(configured) ? stationDir(root).resolve(TRASH_DIR)
                : Paths.get(configured).toAbsolutePath().normalize();
        // 回收站必须落在媒体根的站内目录下：否则软删会把文件搬出媒体根，恢复时越界
        if (!trash.startsWith(stationDir(root)))
        {
            throw new MediaException(500, "qtools.media.trashPath 必须位于 " + stationDir(root));
        }
        return trash;
    }

    @Override
    public Path resolveTrashDir(String kind, Integer rootId)
    {
        if (kind == null || !TRASH_KINDS.contains(kind))
        {
            throw MediaException.badRequest("invalid kind");
        }
        if (rootId != null && rootId <= 0)
        {
            throw MediaException.badRequest("invalid rootId");
        }
        Path base = trashRoot().resolve(kind);
        // root_id 为 NULL 的存量行沿用老结构 trash/<kind>/；非空才分桶到 trash/<kind>/<rootId>/
        Path dir = rootId == null ? base : base.resolve(String.valueOf(rootId));
        try
        {
            Files.createDirectories(dir);
            return dir.toRealPath();
        }
        catch (IOException e)
        {
            throw new MediaException(500, "回收站目录不可用: " + dir);
        }
    }

    @Override
    public Integer rootIdOf(Path path)
    {
        if (path == null)
        {
            return null;
        }
        Path real = path.toAbsolutePath().normalize();
        try
        {
            real = real.toRealPath();
        }
        catch (IOException | RuntimeException e)
        {
            // 文件已经不在了：退回规范化路径，下面的 startsWith 仍然成立
        }
        if (real.startsWith(rootDir()))
        {
            return null;
        }
        List<MediaRoot> roots;
        try
        {
            roots = mediaRootMapper.selectRootList();
        }
        catch (RuntimeException e)
        {
            return null;
        }
        if (roots == null)
        {
            return null;
        }
        for (MediaRoot root : roots)
        {
            if (root == null || root.getId() == null || StringUtils.isEmpty(root.getPath())
                    || !"root".equalsIgnoreCase(root.getKind()))
            {
                continue;
            }
            try
            {
                if (real.startsWith(Paths.get(root.getPath()).toAbsolutePath().normalize().toRealPath()))
                {
                    return root.getId();
                }
            }
            catch (IOException | RuntimeException e)
            {
                // 根没挂盘 / 路径写错：跳过，继续比下一个根
            }
        }
        return null;
    }

    @Override
    public String relativize(Path path)
    {
        Path root = rootDir();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root))
        {
            throw MediaException.forbidden();
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    /**
     * 视频缩略图：内容寻址 + 回退
     *
     * <p>P4 起封面按<b>内容</b>落盘：{@code thumbs/v/<content_hash>.jpg}。{@code videoId} 是
     * {@code sha1(rel_path)[:12]}，改名 / 移动就会变；{@code content_hash} 不会，因此同一份内容
     * 改名后仍复用同一张封面。</p>
     *
     * <p>查找顺序：<b>先 {@code <content_hash>.jpg}，命中不了再回退 {@code <id>.jpg}</b>。
     * 哈希未回填、老库还没重建、产物缺失时都自然落到老命名，所以新老文件共存、不迁移即可用。</p>
     *
     * <p>对外 URL 依旧是 {@code /api/media/thumb/v/<id>}：只在服务端内部换文件，前端零改动。</p>
     */
    @Override
    public Path resolveThumbVideo(String id)
    {
        if (id == null || !SAFE_ID.matcher(id).matches())
        {
            throw MediaException.forbidden();
        }
        Path dir = thumbsDir().resolve(THUMB_VIDEO_DIR);
        Path contentAddressed = contentAddressedThumb(dir, id);
        if (contentAddressed != null)
        {
            return contentAddressed;
        }
        // 回退：老命名 thumbs/v/<videoId>.jpg
        return requireFile(dir, id + JPG_SUFFIX);
    }

    /**
     * 内容寻址封面 thumbs/v/<content_hash>.jpg
     *
     * @return 命中返回真实路径；哈希未回填、产物还没生成、读库异常都返回 null，由调用方回退老命名
     */
    private Path contentAddressedThumb(Path dir, String videoId)
    {
        String hash;
        try
        {
            hash = mediaVideoMapper.selectContentHashById(videoId);
        }
        catch (RuntimeException e)
        {
            // 读库失败按「没有哈希」处理：退到老命名，别让缩略图接口跟着库一起挂
            return null;
        }
        if (StringUtils.isEmpty(hash))
        {
            return null;
        }
        String name = hash.trim();
        if (!SAFE_CONTENT_HASH.matcher(name).matches())
        {
            return null;
        }
        Path target = dir.resolve(name + JPG_SUFFIX).normalize();
        return target.startsWith(dir) && Files.isRegularFile(target) ? target : null;
    }

    @Override
    public Path resolveThumbPhoto(String albumId, String index, boolean preview)
    {
        if (albumId == null || !SAFE_ALBUM_ID.matcher(albumId).matches())
        {
            throw MediaException.forbidden();
        }
        if (index == null || !SAFE_INDEX.matcher(index).matches())
        {
            throw MediaException.forbidden();
        }
        String name = index.length() >= THUMB_INDEX_WIDTH ? index
                : "0".repeat(THUMB_INDEX_WIDTH - index.length()) + index;
        Path base = thumbsDir().resolve(preview ? PREVIEW_PHOTO_DIR : THUMB_PHOTO_DIR).resolve(albumId);
        return requireFile(base, name + JPG_SUFFIX);
    }

    @Override
    public String contentType(Path path)
    {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0)
        {
            return DEFAULT_MIME;
        }
        String mime = MIME.get(name.substring(dot).toLowerCase());
        return mime != null ? mime : DEFAULT_MIME;
    }

    @Override
    public String toVtt(String text, boolean srt)
    {
        String source = stripBom(text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
        if (!srt)
        {
            // 已经是 vtt（或其它文本）时只补头，不做时间轴改写
            return source.stripLeading().startsWith(VTT_HEADER) ? source : VTT_HEADER + "\n\n" + source;
        }
        StringBuilder cues = new StringBuilder();
        for (String[] block : splitBlocks(source))
        {
            String cue = convertBlock(block);
            if (cue == null)
            {
                continue;
            }
            if (cues.length() > 0)
            {
                cues.append("\n\n");
            }
            cues.append(cue);
        }
        return VTT_HEADER + "\n\n" + cues + "\n";
    }

    // ------------------------------------------------------------------ 路径安全

    /**
     * 库根 realpath（多库根）
     *
     * @param rootId null = 配置 {@code qtools.media.rootPath}（沿用原有「配置变了就重算」的缓存）；
     *               非空 = {@code media_root} 里该行的 {@code path}
     */
    private Path rootDir(Integer rootId)
    {
        if (rootId == null)
        {
            return rootDir();
        }
        MediaRoot root;
        try
        {
            root = mediaRootMapper.selectRootById(rootId);
        }
        catch (RuntimeException e)
        {
            // 表不存在（没跑 media_portability.sql）/ 库不可用：显式指定的根查不到就是查不到
            throw new MediaException(500, "读取库根失败（media_root）: id=" + rootId);
        }
        if (root == null || StringUtils.isEmpty(root.getPath()))
        {
            throw MediaException.notFound("库根不存在: media_root.id=" + rootId);
        }
        try
        {
            return Paths.get(root.getPath()).toAbsolutePath().normalize().toRealPath();
        }
        catch (IOException | RuntimeException e)
        {
            // 第二个根没挂盘 / 路径写错：按「找不到」处理，不把接口打成 500
            throw MediaException.notFound("库根不可用: " + root.getPath() + "（media_root.id=" + rootId + "）");
        }
    }

    /**
     * 多根兜底用的其它媒体根（{@code kind='root'}，按 id 升序，排除当前默认根）
     *
     * <p>根不存在 / 不可访问 / {@code media_root} 没建 / 读库失败一律<b>静默跳过</b>：
     * 第二个根没挂盘时既不能报错，也不该影响默认根的正常访问。</p>
     */
    private List<Path> otherRoots(Path primary)
    {
        List<MediaRoot> roots;
        try
        {
            roots = mediaRootMapper.selectRootList();
        }
        catch (RuntimeException e)
        {
            return List.of();
        }
        if (roots == null || roots.isEmpty())
        {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (MediaRoot root : roots)
        {
            if (root == null || StringUtils.isEmpty(root.getPath()) || !"root".equalsIgnoreCase(root.getKind()))
            {
                continue;
            }
            Path dir;
            try
            {
                dir = Paths.get(root.getPath()).toAbsolutePath().normalize().toRealPath();
            }
            catch (IOException | RuntimeException e)
            {
                continue;
            }
            if (dir.equals(primary) || out.contains(dir))
            {
                continue;
            }
            out.add(dir);
        }
        return out;
    }

    /** 媒体根（realpath） */
    private Path rootDir()
    {
        String config = mediaProperties.getRootPath();
        Path cached = rootReal;
        if (cached != null && Objects.equals(config, rootConfig))
        {
            return cached;
        }
        synchronized (this)
        {
            if (rootReal != null && Objects.equals(config, rootConfig))
            {
                return rootReal;
            }
            Path real = toRealDir(config, "qtools.media.rootPath");
            rootConfig = config;
            rootReal = real;
            return real;
        }
    }

    /** 缩略图根（realpath）；thumbs/p、thumbs/pv、thumbs/v 的父目录 */
    private Path thumbsDir()
    {
        String config = mediaProperties.getThumbsPath();
        Path cached = thumbsReal;
        if (cached != null && Objects.equals(config, thumbsConfig))
        {
            return cached;
        }
        synchronized (this)
        {
            if (thumbsReal != null && Objects.equals(config, thumbsConfig))
            {
                return thumbsReal;
            }
            Path real = toRealDir(config, "qtools.media.thumbsPath");
            thumbsConfig = config;
            thumbsReal = real;
            return real;
        }
    }

    private Path stationDir(Path root)
    {
        return root.resolve(STATION_DIR);
    }

    private Path toRealDir(String configured, String key)
    {
        if (StringUtils.isEmpty(configured) || configured.trim().isEmpty())
        {
            throw new MediaException(500, key + " 未配置（请在 application.yml 或环境变量 "
                    + key.replace('.', '_').toUpperCase() + " 中提供）");
        }
        try
        {
            return Paths.get(configured).toAbsolutePath().normalize().toRealPath();
        }
        catch (IOException e)
        {
            throw new MediaException(500, key + " 不可访问: " + configured);
        }
    }

    /** 在某个根内定位普通文件：越界 403 / 不存在 404 */
    private Path fileIn(Path root, String rel)
    {
        Path real = toRealPath(resolveInside(root, rel));
        if (!real.startsWith(root))
        {
            throw MediaException.forbidden();
        }
        if (real.startsWith(stationDir(root)))
        {
            throw MediaException.forbidden();
        }
        if (!Files.isRegularFile(real))
        {
            throw MediaException.notFound();
        }
        return real;
    }

    /** 在某个根内定位目录：越界 403 / 不存在 404（站内目录也不允许作为删除/隐藏目标） */
    private Path dirIn(Path root, String rel)
    {
        Path real = toRealPath(resolveInside(root, rel));
        if (!real.startsWith(root))
        {
            throw MediaException.forbidden();
        }
        if (real.startsWith(stationDir(root)))
        {
            // 站内目录（_媒体站）下的任何对象都不得作为删除/隐藏目标
            throw MediaException.forbidden();
        }
        if (!Files.isDirectory(real))
        {
            throw MediaException.notFound();
        }
        return real;
    }

    /**
     * 相对路径的语法白名单：拒绝 NUL / 绝对路径 / 盘符 / UNC
     *
     * <p>与根无关，所以在多根兜底之前先判一次；{@link #resolveInside} 内部还会再判一次，
     * 保证任何调用路径都过这道闸。</p>
     */
    private void checkRelSyntax(String rel)
    {
        if (rel.indexOf('\0') >= 0)
        {
            throw MediaException.forbidden();
        }
        char first = rel.charAt(0);
        if (first == '/' || first == '\\')
        {
            // 同时覆盖 UNC（\\server\share）与站点绝对路径
            throw MediaException.forbidden();
        }
        if (rel.length() > 1 && rel.charAt(1) == ':' && Character.isLetter(first))
        {
            throw MediaException.forbidden();
        }
    }

    /** 拒绝绝对路径/盘符/UNC/前导分隔符，并把 rel 解析到 root 内 */
    private Path resolveInside(Path root, String rel)
    {
        checkRelSyntax(rel);
        Path target;
        try
        {
            target = root.resolve(rel).normalize();
        }
        catch (InvalidPathException e)
        {
            throw MediaException.forbidden();
        }
        if (!target.startsWith(root))
        {
            throw MediaException.forbidden();
        }
        return target;
    }

    /**
     * 目标可以还不存在：取最近的已存在祖先做 realpath，仍必须在根内
     *
     * <p>{@code rootDir} 系列返回的已经是 realpath，所以这里直接比对即可。用于恢复写回：
     * {@code rel} 来自数据库，父目录里若有符号链接指向根外，必须先拦下再创建/移动。</p>
     */
    private void requireRealInside(Path root, Path target)
    {
        Path probe = target;
        while (probe != null && !Files.exists(probe))
        {
            probe = probe.getParent();
        }
        if (probe == null)
        {
            return;
        }
        try
        {
            if (!probe.toRealPath().startsWith(root))
            {
                throw MediaException.forbidden();
            }
        }
        catch (IOException e)
        {
            throw MediaException.forbidden();
        }
    }

    private Path toRealPath(Path target)
    {
        try
        {
            return target.toRealPath();
        }
        catch (NoSuchFileException e)
        {
            throw MediaException.notFound();
        }
        catch (IOException e)
        {
            // 权限不足、链路异常等一律按越权处理，避免泄漏文件是否存在
            throw MediaException.forbidden();
        }
    }

    /** 拼接后仍须落在 base 内且为真实普通文件 */
    private Path requireFile(Path base, String name)
    {
        Path target = base.resolve(name).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target))
        {
            throw MediaException.notFound();
        }
        return target;
    }

    // ------------------------------------------------------------------ SRT -> VTT

    private static String stripBom(String text)
    {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** 按空行切块，逐行去尾部空白 */
    private static java.util.List<String[]> splitBlocks(String source)
    {
        java.util.List<String[]> blocks = new java.util.ArrayList<>();
        java.util.List<String> current = new java.util.ArrayList<>();
        for (String raw : source.split("\n", -1))
        {
            String line = stripTrailing(raw);
            if (line.isEmpty())
            {
                if (!current.isEmpty())
                {
                    blocks.add(current.toArray(new String[0]));
                    current.clear();
                }
                continue;
            }
            current.add(line);
        }
        if (!current.isEmpty())
        {
            blocks.add(current.toArray(new String[0]));
        }
        return blocks;
    }

    /** 单个字幕块转 VTT cue；纯 WEBVTT 头返回 null 表示丢弃 */
    private static String convertBlock(String[] block)
    {
        int start = 0;
        // 纯数字序号行 + 下一行含时间轴 => 去掉序号
        if (block.length > 1 && isDigits(block[0]) && block[1].contains("-->"))
        {
            start = 1;
        }
        if (start >= block.length)
        {
            return null;
        }
        String head = block[start];
        java.util.regex.Matcher m = TIMELINE.matcher(head);
        if (!m.matches())
        {
            if (VTT_HEADER.equals(head) || head.startsWith(VTT_HEADER + " "))
            {
                return null;
            }
            return joinLines(block, start);
        }
        StringBuilder cue = new StringBuilder();
        cue.append(fixTime(m.group(1))).append(" --> ").append(fixTime(m.group(2)));
        cue.append(m.group(3) == null ? "" : m.group(3));
        for (int i = start + 1; i < block.length; i++)
        {
            cue.append('\n').append(block[i]);
        }
        return cue.toString();
    }

    private static final java.util.regex.Pattern TIMELINE =
            java.util.regex.Pattern.compile("^\\s*(\\S+)\\s*-->\\s*(\\S+)([\\s\\S]*)$");

    private static final java.util.regex.Pattern TIME_FULL =
            java.util.regex.Pattern.compile("^(\\d{1,3}):(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})$");

    private static final java.util.regex.Pattern TIME_SHORT =
            java.util.regex.Pattern.compile("^(\\d{1,3}):(\\d{1,2})[,.](\\d{1,3})$");

    /** 时间轴归一化：逗号转点、补足 2/3 位 */
    private static String fixTime(String time)
    {
        java.util.regex.Matcher full = TIME_FULL.matcher(time);
        if (full.matches())
        {
            return padLeft(full.group(1), 2) + ':' + padLeft(full.group(2), 2) + ':' + padLeft(full.group(3), 2)
                    + '.' + (full.group(4) + "000").substring(0, 3);
        }
        java.util.regex.Matcher shortForm = TIME_SHORT.matcher(time);
        if (shortForm.matches())
        {
            return "00:" + padLeft(shortForm.group(1), 2) + ':' + padLeft(shortForm.group(2), 2)
                    + '.' + (shortForm.group(3) + "000").substring(0, 3);
        }
        return time;
    }

    private static String padLeft(String value, int width)
    {
        return value.length() >= width ? value : "0".repeat(width - value.length()) + value;
    }

    private static boolean isDigits(String value)
    {
        if (value.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < value.length(); i++)
        {
            if (!Character.isDigit(value.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    private static String stripTrailing(String value)
    {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t'))
        {
            end--;
        }
        return value.substring(0, end);
    }

    private static String joinLines(String[] block, int from)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < block.length; i++)
        {
            if (sb.length() > 0)
            {
                sb.append('\n');
            }
            sb.append(block[i]);
        }
        return sb.toString();
    }
}
