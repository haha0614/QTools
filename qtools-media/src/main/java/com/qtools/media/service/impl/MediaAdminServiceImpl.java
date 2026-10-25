package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.qtools.common.core.page.TableDataInfo;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaAlbum;
import com.qtools.media.domain.MediaPerson;
import com.qtools.media.domain.MediaPhoto;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.domain.MediaVideoActor;
import com.qtools.media.domain.MediaVideoTag;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaAdminMapper;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaAdminService;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaIndexService;

/**
 * 管理端媒体库 服务实现
 *
 * <p>软删安全规则（与方案 §7「删除一律软删」对齐）：</p>
 * <ol>
 *   <li>源路径先过 {@link IMediaFileService} 的白名单校验：必须在媒体根内、必须不是站内目录
 *       {@code _媒体站}（回收站本身就在站内目录下，因此永远不可能成为软删目标）；</li>
 *   <li>只用 {@link Files#move} 移动到 {@code _媒体站/trash/<kind>/<id>__<原名>}，成功后才置
 *       {@code hidden=1}；全程没有任何 delete 调用；</li>
 *   <li>目标已存在时加时间戳后缀，绝不覆盖；</li>
 *   <li>相册软删会移动整个物理目录，因此先检查该目录是否被其它相册共用
 *       （念念 144 本共用 {@code 写真/念念 Nian Nian}），共用时 409 拒绝，否则会一次毁掉 144 册。</li>
 * </ol>
 *
 * @author qtools
 */
@Service
public class MediaAdminServiceImpl implements IMediaAdminService
{
    private static final Logger log = LoggerFactory.getLogger(MediaAdminServiceImpl.class);

    private static final String KIND_VIDEO = "video";

    private static final String KIND_ALBUM = "album";

    private static final String KIND_PHOTO = "photo";

    /** 相册详情里带的图片张数上限 */
    private static final int DETAIL_PHOTO_LIMIT = 20;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private static final DateTimeFormatter SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** id 前缀分隔符：trash/<kind>/<id>__<原名> */
    private static final String TRASH_SEP = "__";

    /** 回收站里的库根桶目录名：trash/<kind>/<rootId>/（纯数字；条目名一定含 __，不会误判） */
    private static final Pattern ROOT_BUCKET = Pattern.compile("^\\d{1,10}$");

    /** 重复软删时嵌在文件名里的时间戳（yyyyMMddHHmmss，必要时带 _n 序号） */
    private static final Pattern TRASH_STAMP = Pattern.compile("^\\d{14}(_\\d+)?$");

    /** 回收站类目的固定顺序（列表 / 统计共用） */
    private static final List<String> TRASH_KINDS = List.of(KIND_VIDEO, KIND_ALBUM, KIND_PHOTO);

    /** 回收站列表默认页大小 / 上限（磁盘枚举后内存分页，防一次拉全量） */
    private static final int TRASH_PAGE_SIZE = 20;

    private static final int TRASH_PAGE_MAX = 200;

    /** 首次软删无内嵌时间戳 → 视为最早（排序键为空串），与 restore 的取法保持一致 */
    private static final Comparator<TrashEntry> TRASH_ORDER =
            Comparator.comparing(entry -> entry.stamp() == null ? "" : entry.stamp());

    /** 库根一致性抽样的条数（自检必须是常数级开销，不能全量扫库） */
    private static final int ROOT_SAMPLE = 200;

    @Autowired
    private MediaAdminMapper mediaAdminMapper;

    @Autowired
    private MediaVideoMapper mediaVideoMapper;

    @Autowired
    private MediaPersonMapper mediaPersonMapper;

    /** 多库根清单（自检只列不改） */
    @Autowired
    private MediaRootMapper mediaRootMapper;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private IMediaIndexService mediaIndexService;

    @Autowired
    private MediaProperties mediaProperties;

    /** 盘上根目录 {@code qtools.home}（application.yml 缺省 E:/111），只用于配置自检回显 */
    @Value("${qtools.home:}")
    private String home;

    // ------------------------------------------------------------------ 列表

    @Override
    public TableDataInfo selectVideoPage(String keyword, String folder, String person, String vcodec,
            String hidden, String sort)
    {
        List<MediaVideo> rows = mediaAdminMapper.selectVideoPage(trimToNull(keyword), trimToNull(folder),
                trimToNull(person), trimToNull(vcodec), normalizeHidden(hidden), trimToNull(sort));
        long total = new PageInfo<>(rows).getTotal();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (MediaVideo video : rows)
        {
            out.add(videoRow(video));
        }
        return dataTable(out, total);
    }

    @Override
    public TableDataInfo selectAlbumPage(String keyword, Long personId, String hidden, String sort)
    {
        List<MediaAlbum> rows = mediaAdminMapper.selectAlbumPage(trimToNull(keyword), personId,
                normalizeHidden(hidden), trimToNull(sort));
        long total = new PageInfo<>(rows).getTotal();
        Map<Long, String> personNames = loadPersonNames();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (MediaAlbum album : rows)
        {
            out.add(albumRow(album, personNames));
        }
        return dataTable(out, total);
    }

    @Override
    public TableDataInfo selectPhotoPage(String albumId)
    {
        if (StringUtils.isEmpty(albumId))
        {
            throw MediaException.badRequest("missing albumId");
        }
        List<MediaPhoto> rows = mediaAdminMapper.selectPhotoPage(albumId);
        long total = new PageInfo<>(rows).getTotal();
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (MediaPhoto photo : rows)
        {
            out.add(photoRow(photo));
        }
        return dataTable(out, total);
    }

    @Override
    public List<Map<String, Object>> albumPhotos(String albumId)
    {
        if (StringUtils.isEmpty(albumId))
        {
            throw MediaException.badRequest("missing albumId");
        }
        List<MediaPhoto> rows = mediaAdminMapper.selectAlbumPhotos(albumId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (MediaPhoto photo : rows)
        {
            out.add(photoRow(photo));
        }
        return out;
    }

    // ------------------------------------------------------------------ 缺失文件（P2-D）

    /** 缺失记录里每行最多给多少条（kind=all 时两条查询都按它兜底，防止一次拉爆内存） */
    private static final int MISSING_PAGE_MAX = 200;

    @Override
    public Map<String, Object> missingList(String kind, String albumId, int pageNum, int pageSize)
    {
        Mode mode = Mode.of(kind);
        int page = Math.max(1, pageNum);
        int size = Math.min(MISSING_PAGE_MAX, Math.max(1, pageSize));
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0L;
        if (mode.photos)
        {
            PageHelper.startPage(page, size);
            List<MediaPhoto> photos = mediaAdminMapper.selectMissingPhotoPage(trimToNull(albumId));
            total += "photo".equals(mode.name) ? new PageInfo<>(photos).getTotal() : countMissingPhotos(albumId);
            for (MediaPhoto photo : photos)
            {
                rows.add(missingPhotoRow(photo));
            }
        }
        if (mode.videos)
        {
            PageHelper.startPage(page, size);
            List<MediaVideo> videos = mediaAdminMapper.selectMissingVideoPage();
            total += "video".equals(mode.name) ? new PageInfo<>(videos).getTotal() : countMissingVideos();
            for (MediaVideo video : videos)
            {
                rows.add(missingVideoRow(video));
            }
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) -> String.valueOf(row.get("missingSince"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("kind", mode.name);
        return out;
    }

    @Override
    public Map<String, Object> pruneMissing(String kind, List<String> ids, boolean all)
    {
        Mode mode = Mode.of(kind);
        if (!all && (ids == null || ids.isEmpty()))
        {
            throw MediaException.badRequest("缺少 ids（或传 all:true 清空该类型的全部缺失记录）");
        }
        int deleted = 0;
        int photoDeleted = 0;
        int videoDeleted = 0;
        if (mode.videos)
        {
            List<String> videoIds = all ? mediaAdminMapper.selectMissingVideoIds() : ids;
            if (videoIds != null && !videoIds.isEmpty())
            {
                // 附属行先删，media_video_tag / actor / marker 里不留指向已删视频的孤儿行
                mediaAdminMapper.deleteVideoTagsByVideoIds(videoIds);
                mediaAdminMapper.deleteVideoActorsByVideoIds(videoIds);
                mediaAdminMapper.deleteVideoMarkersByVideoIds(videoIds);
                videoDeleted = mediaAdminMapper.deleteMissingVideos(videoIds);
                deleted += videoDeleted;
            }
        }
        if (mode.photos)
        {
            List<Long> photoIds = null;
            if (!all)
            {
                photoIds = new ArrayList<>();
                for (String raw : ids)
                {
                    try
                    {
                        photoIds.add(Long.valueOf(raw));
                    }
                    catch (NumberFormatException e)
                    {
                        throw MediaException.badRequest("photo id 必须是数字: " + raw);
                    }
                }
            }
            photoDeleted = mediaAdminMapper.deleteMissingPhotos(photoIds);
            deleted += photoDeleted;
        }
        log.info("管理端清理缺失记录 kind={} all={} deleted={}（photo={} video={}）", mode.name, all, deleted,
                photoDeleted, videoDeleted);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", deleted);
        out.put("kind", mode.name);
        out.put("all", all);
        out.put("photos", photoDeleted);
        out.put("videos", videoDeleted);
        return out;
    }

    /** kind 归一化：photo / video / all（缺省 photo，与前端弹窗的调用方式一致） */
    private enum Mode
    {
        PHOTO("photo", true, false),
        VIDEO("video", false, true),
        ALL("all", true, true);

        private final String name;
        private final boolean photos;
        private final boolean videos;

        Mode(String name, boolean photos, boolean videos)
        {
            this.name = name;
            this.photos = photos;
            this.videos = videos;
        }

        static Mode of(String kind)
        {
            if (StringUtils.isEmpty(kind))
            {
                return PHOTO;
            }
            String k = kind.trim().toLowerCase();
            if ("video".equals(k))
            {
                return VIDEO;
            }
            if ("all".equals(k))
            {
                return ALL;
            }
            if ("photo".equals(k))
            {
                return PHOTO;
            }
            throw MediaException.badRequest("kind 只接受 photo / video / all: " + kind);
        }
    }

    private long countMissingPhotos(String albumId)
    {
        return new PageInfo<>(mediaAdminMapper.selectMissingPhotoPage(trimToNull(albumId))).getTotal();
    }

    private long countMissingVideos()
    {
        return new PageInfo<>(mediaAdminMapper.selectMissingVideoPage()).getTotal();
    }

    /** 缺失图片行：id/相册/路径/最后 seen（前端 photoSource=db 的缺失列表直接用它） */
    private Map<String, Object> missingPhotoRow(MediaPhoto photo)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", "photo");
        node.put("id", photo.getId());
        node.put("albumId", photo.getAlbumId());
        node.put("seq", photo.getSeq());
        node.put("title", null);
        node.put("relPath", photo.getRelPath());
        node.put("bytes", photo.getBytes());
        node.put("contentHash", photo.getContentHash());
        node.put("missing", true);
        node.put("lastSeen", formatDate(photo.getLastSeen()));
        // 语义：最后一次在磁盘上看到它的时间——文件是「这之后」消失的，故上界即 missingSince
        node.put("missingSince", formatDate(photo.getLastSeen()));
        return node;
    }

    private Map<String, Object> missingVideoRow(MediaVideo video)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("kind", "video");
        node.put("id", video.getId());
        node.put("albumId", null);
        node.put("seq", null);
        node.put("title", video.getTitle());
        node.put("relPath", video.getRelPath());
        node.put("bytes", video.getSize());
        node.put("contentHash", video.getContentHash());
        node.put("missing", true);
        node.put("lastSeen", formatDate(video.getLastSeen()));
        node.put("missingSince", formatDate(video.getLastSeen()));
        return node;
    }

    // ------------------------------------------------------------------ 详情
    @Override
    public Map<String, Object> selectDetail(String kind, String id)
    {
        requireKind(kind);
        if (StringUtils.isEmpty(id))
        {
            throw MediaException.badRequest("missing id");
        }
        if (KIND_VIDEO.equals(kind))
        {
            MediaVideo video = mediaAdminMapper.selectVideoAnyById(id);
            if (video == null)
            {
                throw MediaException.notFound("video not found");
            }
            Map<String, Object> node = videoRow(video);
            List<List<String>> tags = new ArrayList<>();
            for (MediaVideoTag tag : mediaVideoMapper.selectTagListByVideoId(id))
            {
                tags.add(List.of(tag.getFacet(), tag.getName()));
            }
            List<String> actors = new ArrayList<>();
            for (MediaVideoActor actor : mediaVideoMapper.selectActorListByVideoId(id))
            {
                actors.add(actor.getName());
            }
            node.put("doc", video.getDocPath());
            node.put("sub", video.getSubPath());
            node.put("tags", tags);
            node.put("actors", actors);
            return node;
        }
        if (KIND_ALBUM.equals(kind))
        {
            MediaAlbum album = mediaAdminMapper.selectAlbumAnyById(id);
            if (album == null)
            {
                throw MediaException.notFound("album not found");
            }
            Map<String, Object> node = albumRow(album, loadPersonNames());
            node.put("note", album.getNote());
            node.put("sort", album.getSort());
            List<Map<String, Object>> photos = new ArrayList<>();
            for (MediaPhoto photo : mediaAdminMapper.selectPhotoHeadByAlbumId(id, DETAIL_PHOTO_LIMIT))
            {
                photos.add(photoRow(photo));
            }
            node.put("photos", photos);
            return node;
        }
        MediaPhoto photo = resolvePhoto(id, null, null);
        return photoRow(photo);
    }

    // ------------------------------------------------------------------ 隐藏位

    @Override
    public Map<String, Object> updateHidden(String kind, String id, Boolean hidden, String albumId, Integer seq)
    {
        requireKind(kind);
        if (hidden == null)
        {
            throw MediaException.badRequest("missing hidden");
        }
        int flag = hidden ? 1 : 0;
        int updated;
        String echoId;
        if (KIND_VIDEO.equals(kind))
        {
            requireVideo(id);
            updated = mediaAdminMapper.updateVideoHidden(id, flag);
            echoId = id;
        }
        else if (KIND_ALBUM.equals(kind))
        {
            requireAlbum(id);
            updated = mediaAdminMapper.updateAlbumHidden(id, flag);
            echoId = id;
        }
        else
        {
            MediaPhoto photo = resolvePhoto(id, trimToNull(albumId), seq);
            updated = mediaAdminMapper.updatePhotoHidden(photo.getId(), flag);
            echoId = photo.getAlbumId() + ":" + photo.getSeq();
        }
        mediaIndexService.invalidateSnapshot();
        log.info("管理端设置隐藏位 kind={} id={} hidden={} rows={}", kind, echoId, flag, updated);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("kind", kind);
        out.put("id", echoId);
        out.put("hidden", hidden);
        return out;
    }

    // ------------------------------------------------------------------ 软删 / 恢复

    @Override
    public Map<String, Object> delete(String kind, String id)
    {
        requireKind(kind);
        Path source;
        String rel;
        String trashId;
        Integer rootId;
        if (KIND_VIDEO.equals(kind))
        {
            MediaVideo video = requireVideo(id);
            rel = video.getRelPath();
            rootId = video.getRootId();
            source = mediaFileService.resolveMedia(rel, rootId);
            trashId = video.getId();
        }
        else if (KIND_ALBUM.equals(kind))
        {
            MediaAlbum album = requireAlbum(id);
            int shared = mediaAdminMapper.countAlbumByDir(album.getDir(), album.getId());
            if (shared > 0)
            {
                // 念念 144 本相册共用同一物理目录：移动目录会连带删掉其它 143 本
                throw MediaException.conflict("目录 " + album.getDir() + " 被 " + shared
                        + " 本其它相册共用，不能单独软删；请先给这些相册分目录或改用隐藏");
            }
            rel = album.getDir();
            source = mediaFileService.resolveMediaDir(rel);
            // 相册表没有 root_id：按这个目录实际落在哪个根判断，否则会搬进默认根的桶里
            rootId = mediaFileService.rootIdOf(source);
            trashId = album.getId();
        }
        else
        {
            MediaPhoto photo = resolvePhoto(id, null, null);
            rel = photo.getRelPath();
            rootId = photo.getRootId();
            source = mediaFileService.resolveMedia(rel, rootId);
            trashId = String.valueOf(photo.getId());
        }

        // 按记录所属的根分桶：trash/<kind>/<rootId>/，root_id 为 NULL 时沿用老结构 trash/<kind>/
        Path targetDir = mediaFileService.resolveTrashDir(kind, rootId);
        Path target = uniqueTarget(targetDir, trashId, source.getFileName().toString());
        try
        {
            moveOrCopy(source, target);
        }
        catch (IOException e)
        {
            log.error("软删移动失败 kind={} id={} {} -> {}", kind, trashId, source, target, e);
            throw new MediaException(500, "移动失败（跨盘或文件被占用）：" + e.getMessage());
        }

        int updated = setHidden(kind, trashId, 1);
        mediaIndexService.invalidateSnapshot();
        log.info("管理端软删 kind={} id={} {} -> {} rows={}", kind, trashId, source, target, updated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", true);
        out.put("kind", kind);
        out.put("id", trashId);
        out.put("from", rel);
        out.put("to", mediaFileService.relativize(target));
        out.put("rootId", rootId);
        out.put("updated", updated);
        out.put("hidden", true);
        return out;
    }

    @Override
    public Map<String, Object> restore(String kind, String id)
    {
        requireKind(kind);
        Path source = null;
        String storedId;
        String rel;
        Integer rootId;
        if (KIND_VIDEO.equals(kind))
        {
            MediaVideo video = requireVideo(id);
            storedId = video.getId();
            rel = video.getRelPath();
            rootId = video.getRootId();
        }
        else if (KIND_ALBUM.equals(kind))
        {
            MediaAlbum album = requireAlbum(id);
            storedId = album.getId();
            rel = album.getDir();
            rootId = albumRootId(album.getId());
        }
        else
        {
            MediaPhoto photo = resolvePhoto(id, null, null);
            storedId = String.valueOf(photo.getId());
            rel = photo.getRelPath();
            rootId = photo.getRootId();
        }

        // 回收站里以该 id 开头的条目（含各 root_id 桶，老条目仍能被找到）；
        // 名字里带时间戳后缀的排在后面，即最近一次软删
        List<TrashEntry> candidates = listTrashEntries(kind, storedId);
        if (candidates.isEmpty())
        {
            throw MediaException.notFound("回收站中没有该对象（" + storedId + "）");
        }
        source = candidates.get(candidates.size() - 1).path();

        // 写回记录的所属根（root_id 为 NULL → 默认根）：resolveWriteTarget 内部
        // 再做一次 resolveInside + 最近存在祖先的 realpath 复核，越界一律 403
        Path destination = mediaFileService.resolveWriteTarget(rel, rootId);
        if (Files.exists(destination))
        {
            throw MediaException.conflict("原路径已存在，未覆盖：" + rel);
        }
        try
        {
            if (destination.getParent() != null)
            {
                Files.createDirectories(destination.getParent());
            }
            moveOrCopy(source, destination);
        }
        catch (IOException e)
        {
            log.error("恢复移动失败 kind={} id={} {} -> {}", kind, storedId, source, destination, e);
            throw new MediaException(500, "移动失败（跨盘或文件被占用）：" + e.getMessage());
        }

        int updated = setHidden(kind, storedId, 0);
        mediaIndexService.invalidateSnapshot();
        log.info("管理端恢复 kind={} id={} {} -> {} rows={}", kind, storedId, source, destination, updated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", true);
        out.put("kind", kind);
        out.put("id", storedId);
        out.put("from", mediaFileService.relativize(source));
        out.put("to", rel);
        out.put("rootId", rootId);
        out.put("updated", updated);
        out.put("hidden", false);
        return out;
    }

    // ------------------------------------------------------------------ 回收站

    @Override
    public TableDataInfo selectTrashPage(String kind, String keyword, int pageNum, int pageSize)
    {
        String kindFilter = normalizeTrashKind(kind);
        String needle = trimToNull(keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        // 磁盘为准：同 id 多份软删各占一行，按 deletedAt 倒序（最近删的在最前）
        for (TrashEntry entry : scanTrash(kindFilter, true))
        {
            Map<String, Object> row = trashRow(entry);
            if (needle != null && !trashMatches(row, needle))
            {
                continue;
            }
            rows.add(row);
        }
        long total = rows.size();
        int size = pageSize <= 0 ? TRASH_PAGE_SIZE : Math.min(pageSize, TRASH_PAGE_MAX);
        int page = Math.max(1, pageNum);
        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return dataTable(new ArrayList<>(rows.subList(from, to)), total);
    }

    @Override
    public Map<String, Object> trashStats()
    {
        Map<String, Object> byKind = new LinkedHashMap<>();
        for (String kind : TRASH_KINDS)
        {
            long count = 0L;
            long bytes = 0L;
            for (TrashEntry entry : scanTrash(kind))
            {
                count++;
                bytes += entry.bytes();
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("count", count);
            node.put("bytes", bytes);
            byKind.put(kind, node);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // 与 /stats 的 trashCount / trashBytes 同源（同两个方法），不另算一套口径
        out.put("count", trashCount());
        out.put("bytes", trashBytes());
        out.put("byKind", byKind);
        return out;
    }

    @Override
    public Map<String, Object> purgeTrash(String kind, String id, String trashRel)
    {
        requireKind(kind);
        Path target;
        if (StringUtils.isNotEmpty(trashRel))
        {
            // 前端回传的 trashRel 优先：同 id 多份时能精确指定要彻底删掉哪一条
            target = resolveTrashTarget(kind, trashRel);
        }
        else
        {
            if (StringUtils.isEmpty(id))
            {
                throw MediaException.badRequest("missing id");
            }
            List<TrashEntry> entries = listTrashEntries(kind, id);
            if (entries.isEmpty())
            {
                throw MediaException.notFound("回收站中没有该对象（" + id + "）");
            }
            target = requireTrashObject(kind, entries.get(entries.size() - 1).path());
        }

        long bytes = dirBytes(target);
        boolean isDir = Files.isDirectory(target);
        try
        {
            deleteRecursively(target);
        }
        catch (IOException e)
        {
            log.error("回收站彻底删除失败 kind={} id={} {}", kind, id, target, e);
            throw new MediaException(500, "彻底删除失败：" + e.getMessage());
        }
        mediaIndexService.invalidateSnapshot();
        log.info("管理端彻底删除 kind={} id={} {} bytes={} isDir={}", kind, id, target, bytes, isDir);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purged", true);
        out.put("kind", kind);
        out.put("id", id);
        out.put("isDir", isDir);
        out.put("trashRel", mediaFileService.relativize(target));
        out.put("bytes", bytes);
        out.put("freed", bytes);
        return out;
    }

    /** 回收站一行：{kind,id,name,originalRel,orphan,trashRel,bytes,deletedAt,isDir,thumbUrl} */
    private Map<String, Object> trashRow(TrashEntry entry)
    {
        String originalRel = null;
        String thumbUrl = null;
        if (KIND_VIDEO.equals(entry.kind()))
        {
            MediaVideo video = lookupVideo(entry.id());
            if (video != null)
            {
                originalRel = video.getRelPath();
                // 内容寻址 + 回退都在 resolveThumbVideo 里做，这里只负责拼固定格式的 URL
                thumbUrl = "/api/media/thumb/v/" + video.getId();
            }
        }
        else if (KIND_ALBUM.equals(entry.kind()))
        {
            MediaAlbum album = lookupAlbum(entry.id());
            if (album != null)
            {
                originalRel = album.getDir();
                if (StringUtils.isNotEmpty(album.getCoverPath()))
                {
                    thumbUrl = "/api/media/thumb/p/" + album.getId() + "/" + coverSeq(album.getCoverPath());
                }
            }
        }
        else
        {
            MediaPhoto photo = lookupPhoto(entry.id());
            if (photo != null)
            {
                originalRel = photo.getRelPath();
                thumbUrl = thumbUrl(photo.getAlbumId(), photo.getSeq());
            }
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", entry.kind());
        row.put("id", entry.id());
        row.put("rootId", entry.rootId());
        row.put("name", entry.name());
        row.put("originalRel", originalRel);
        // DB 里查不到（记录已删）→ 原路径未知，只能按磁盘对象处理
        row.put("orphan", originalRel == null);
        row.put("trashRel", mediaFileService.relativize(entry.path()));
        row.put("bytes", entry.bytes());
        row.put("deletedAt", formatDate(entry.deletedAt()));
        row.put("isDir", entry.isDir());
        row.put("thumbUrl", thumbUrl);
        return row;
    }

    private static boolean trashMatches(Map<String, Object> row, String needle)
    {
        String lower = needle.toLowerCase();
        for (String key : List.of("name", "originalRel", "id", "kind"))
        {
            Object value = row.get(key);
            if (value != null && String.valueOf(value).toLowerCase().contains(lower))
            {
                return true;
            }
        }
        return false;
    }

    /** 可空查询：回收站里的条目常常已经没有 DB 记录（孤儿），查不到就返回 null */
    private MediaVideo lookupVideo(String id)
    {
        return StringUtils.isEmpty(id) ? null : mediaAdminMapper.selectVideoAnyById(id);
    }

    private MediaAlbum lookupAlbum(String id)
    {
        return StringUtils.isEmpty(id) ? null : mediaAdminMapper.selectAlbumAnyById(id);
    }

    private MediaPhoto lookupPhoto(String id)
    {
        if (id == null || !id.matches("^\\d{1,19}$"))
        {
            return null;
        }
        return mediaAdminMapper.selectPhotoAnyById(Long.valueOf(id));
    }

    /** 相册封面路径（thumbs/p/<id>/0000.jpg）→ 缩略图序号，取不到给 0000 */
    private static String coverSeq(String coverPath)
    {
        Matcher matcher = Pattern.compile("/(\\d+)\\.[A-Za-z0-9]+$").matcher(coverPath.replace('\\', '/'));
        return matcher.find() ? matcher.group(1) : "0000";
    }

    // ------------------------------------------------------------------ 统计 / 人物

    @Override
    public Map<String, Object> stats()
    {
        Map<String, Object> counts = mediaAdminMapper.selectCounts();
        List<Map<String, Object>> byFolder = new ArrayList<>();
        for (Map<String, Object> row : mediaAdminMapper.selectFolderStats())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", row.get("name"));
            node.put("count", longOf(row.get("count")));
            node.put("bytes", longOf(row.get("bytes")));
            byFolder.add(node);
        }
        List<Map<String, Object>> byPerson = new ArrayList<>();
        for (Map<String, Object> row : mediaAdminMapper.selectPersonStats())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", row.get("name"));
            node.put("albums", longOf(row.get("albums")));
            node.put("photos", longOf(row.get("photos")));
            byPerson.add(node);
        }

        long videoBytes = longOf(counts.get("videoBytes"));
        long albumBytes = longOf(counts.get("albumBytes"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videos", longOf(counts.get("videos")));
        out.put("albums", longOf(counts.get("albums")));
        out.put("photos", longOf(counts.get("photos")));
        out.put("people", longOf(counts.get("people")));
        out.put("totalBytes", videoBytes + albumBytes);
        out.put("videoBytes", videoBytes);
        out.put("albumBytes", albumBytes);
        out.put("hiddenVideos", longOf(counts.get("hiddenVideos")));
        out.put("hiddenAlbums", longOf(counts.get("hiddenAlbums")));
        out.put("hiddenPhotos", longOf(counts.get("hiddenPhotos")));
        // 缺失文件（P2-D）：missingCount = 图片 + 视频，另给两个分项便于管理端分别提示
        out.put("missingPhotos", longOf(counts.get("missingPhotos")));
        out.put("missingVideos", longOf(counts.get("missingVideos")));
        out.put("missingCount", longOf(counts.get("missingPhotos")) + longOf(counts.get("missingVideos")));
        out.put("byFolder", byFolder);
        out.put("byPerson", byPerson);
        out.put("trashBytes", trashBytes());
        out.put("trashCount", trashCount());
        return out;
    }

    // ------------------------------------------------------------------ 配置自检

    @Override
    public Map<String, Object> config()
    {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> media = new LinkedHashMap<>();
        try
        {
            media.put("rootPath", dirNode(mediaProperties.getRootPath(), "rootPath", "媒体根", true, false, warnings));
            media.put("thumbsPath",
                    dirNode(mediaProperties.getThumbsPath(), "thumbsPath", "缩略图根", false, true, warnings));
            media.put("trashPath", trashNode(warnings));
            media.put("roots", rootsNode(warnings));
            media.put("ffmpeg", fileNode(mediaProperties.getFfmpeg(), "ffmpeg", "ffmpeg", true, warnings));
            media.put("nodePath", fileNode(mediaProperties.getNodePath(), "nodePath", "Node", true, warnings));
            media.put("scanScript", fileNode(mediaProperties.getScanScript(), "scanScript", "扫描脚本", false, warnings));
            media.put("thumbScript",
                    fileNode(mediaProperties.getThumbScript(), "thumbScript", "缩略图脚本", false, warnings));
            media.put("scanOutPath", scanOutNode(mediaProperties.getScanOutPath(), warnings));
            media.put("maxTranscode", Math.max(1, mediaProperties.getMaxTranscode()));
            media.put("scanArgs", mediaProperties.getScanArgs() == null ? "" : mediaProperties.getScanArgs());
        }
        catch (RuntimeException e)
        {
            // 兜底：自检接口本身绝不能让管理端 500
            warnings.add("自检执行异常: " + message(e));
        }
        checkRootMismatch(warnings);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("home", normalizePath(home));
        out.put("media", media);
        out.put("counts", counts(warnings));
        out.put("warnings", warnings);
        return out;
    }

    /** 库内条数：复用 selectCounts（hidden=0 口径，与 /stats 一致）+ 回收站对象数 */
    private Map<String, Object> counts(List<String> warnings)
    {
        long videos = 0L;
        long albums = 0L;
        long photos = 0L;
        try
        {
            Map<String, Object> row = mediaAdminMapper.selectCounts();
            if (row != null)
            {
                videos = longOf(row.get("videos"));
                albums = longOf(row.get("albums"));
                photos = longOf(row.get("photos"));
            }
        }
        catch (RuntimeException e)
        {
            warnings.add("库内条数查询失败（数据库不可用？）: " + message(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videos", videos);
        out.put("albums", albums);
        out.put("photos", photos);
        try
        {
            out.put("trashEntries", trashCount());
        }
        catch (RuntimeException e)
        {
            // 取不到就不给这个键（契约允许省略），原因进 warnings
            warnings.add("回收站条目统计失败: " + message(e));
        }
        return out;
    }

    /**
     * 库根一致性抽样：{@code rootPath + rel_path} 多数不存在 → 追加「媒体根可能配错」
     *
     * <p>库根换了盘或配错时不会有任何报错，只表现为满屏 404 和破图；这条 warning 让
     * {@code GET /api/media/admin/config} 直接把原因摆出来
     * （见 {@code doc/媒体库可移植性方案.md} §3 P2「库根可能配错」）。</p>
     *
     * <p>只抽样 {@value #ROOT_SAMPLE} 条：库里上万个文件，自检必须是常数级开销。
     * 根未配置 / 不存在 / 不是目录时直接返回 —— 那些情况 {@link #dirNode} 已经报过更准确的
     * warning，不要重复刷屏。</p>
     */
    private void checkRootMismatch(List<String> warnings)
    {
        String rootPath = normalizePath(mediaProperties.getRootPath());
        Path root = toPath(rootPath);
        if (root == null || !probe(root, Files::isDirectory))
        {
            return;
        }
        Path base = root.toAbsolutePath().normalize();
        List<String> relPaths;
        try
        {
            relPaths = mediaAdminMapper.selectSampleRelPaths(ROOT_SAMPLE);
        }
        catch (RuntimeException e)
        {
            warnings.add("库根一致性抽样失败（数据库不可用？）: " + message(e));
            return;
        }
        if (relPaths == null || relPaths.isEmpty())
        {
            return;
        }
        int missing = 0;
        for (String relPath : relPaths)
        {
            if (!probe(resolveUnder(base, relPath), Files::isRegularFile))
            {
                missing++;
            }
        }
        if (missing * 2 > relPaths.size())
        {
            warnings.add("媒体根可能配错：抽样 " + relPaths.size() + " 个视频中有 " + missing
                    + " 个找不到文件（当前库根：" + rootPath + "）");
        }
    }

    /** 相对路径 → 绝对路径；空值、绝对路径、{@code ..} 逃出库根的一律返回 null（视为找不到） */
    private static Path resolveUnder(Path base, String relPath)
    {
        if (relPath == null || relPath.isEmpty())
        {
            return null;
        }
        try
        {
            Path resolved = base.resolve(relPath).normalize();
            return resolved.startsWith(base) ? resolved : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * 多库根清单：{@code [{id,name,kind,path,exists,readable}]}
     *
     * <p>「配了几个根、哪个根现在没挂上」的唯一可视入口。根目录不存在（换盘前先把第二块盘配好）
     * 只记一条 warning，绝不让自检失败，也不影响其它根：这是「第二个根不存在也不给错」的落点。</p>
     */
    private List<Map<String, Object>> rootsNode(List<String> warnings)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        List<MediaRoot> roots;
        try
        {
            roots = mediaRootMapper.selectRootList();
        }
        catch (RuntimeException e)
        {
            warnings.add("多库根清单读取失败（media_root 未建？先执行 doc/media_portability.sql）: " + message(e));
            return out;
        }
        if (roots == null)
        {
            return out;
        }
        for (MediaRoot root : roots)
        {
            if (root == null)
            {
                continue;
            }
            String value = normalizePath(root.getPath());
            Path path = toPath(value);
            boolean exists = probe(path, Files::exists);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", root.getId());
            node.put("name", root.getName());
            node.put("kind", root.getKind());
            node.put("path", value);
            node.put("exists", exists);
            node.put("readable", probe(path, Files::isReadable));
            out.add(node);
            if ("root".equalsIgnoreCase(root.getKind()) && !exists)
            {
                warnings.add("媒体根不可用（media_root.id=" + root.getId() + "）: " + value
                        + "；把盘挂上或改 media_root.path 即可，其它根不受影响");
            }
        }
        return out;
    }

    /**
     * 目录型检查项：{@code {value, exists, readable, writable}}，{@code withFileCount} 时附一级子项数
     *
     * <p>媒体根只读是设计如此，因此它不因 writable=false 报警；缩略图根 / 回收站根不可写才是异常。</p>
     */
    private static Map<String, Object> dirNode(String raw, String key, String label, boolean withFileCount,
            boolean warnIfNotWritable, List<String> warnings)
    {
        String value = normalizePath(raw);
        Path path = toPath(value);
        boolean exists = probe(path, Files::exists);
        boolean directory = probe(path, Files::isDirectory);
        boolean readable = probe(path, Files::isReadable);
        boolean writable = probe(path, Files::isWritable);
        if (value.isEmpty())
        {
            warnings.add(label + " 未配置（qtools.media." + key + "，可用环境变量 " + envNameOf(key) + " 覆盖）");
        }
        else if (path == null)
        {
            warnings.add(label + " 路径非法: " + value);
        }
        else if (!exists)
        {
            warnings.add(label + " 不存在: " + value);
        }
        else
        {
            if (!directory)
            {
                warnings.add(label + " 不是目录: " + value);
            }
            if (!readable)
            {
                warnings.add(label + " 不可读: " + value);
            }
            if (warnIfNotWritable && !writable)
            {
                warnings.add(label + " 不可写: " + value);
            }
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("value", value);
        node.put("exists", exists);
        node.put("readable", readable);
        node.put("writable", writable);
        if (withFileCount)
        {
            long fileCount = directory ? countChildren(path) : 0L;
            node.put("fileCount", fileCount);
            if (directory && fileCount == 0L)
            {
                warnings.add(label + " 为空目录: " + value);
            }
        }
        return node;
    }

    /** 文件型检查项：{@code {value, exists}}，{@code executable} 时附 isExecutable */
    private static Map<String, Object> fileNode(String raw, String key, String label, boolean executable,
            List<String> warnings)
    {
        String value = normalizePath(raw);
        Path path = toPath(value);
        boolean exists = probe(path, Files::exists);
        if (value.isEmpty())
        {
            warnings.add(label + " 未配置（qtools.media." + key + "，可用环境变量 " + envNameOf(key) + " 覆盖）");
        }
        else if (path == null)
        {
            warnings.add(label + " 路径非法: " + value);
        }
        else if (!exists)
        {
            warnings.add(label + " 不存在: " + value);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("value", value);
        node.put("exists", exists);
        if (executable)
        {
            boolean canRun = probe(path, Files::isExecutable);
            node.put("executable", canRun);
            if (exists && !canRun)
            {
                warnings.add(label + " 不可执行: " + value);
            }
        }
        return node;
    }

    /** 扫描产物目录：{@code {value, exists, writable}}（不存在时扫描任务会自动创建） */
    private static Map<String, Object> scanOutNode(String raw, List<String> warnings)
    {
        String value = normalizePath(raw);
        Path path = toPath(value);
        boolean exists = probe(path, Files::exists);
        boolean writable = probe(path, Files::isWritable);
        if (value.isEmpty())
        {
            warnings.add("扫描产物目录 未配置（qtools.media.scanOutPath，可用环境变量 QTOOLS_MEDIA_SCANOUTPATH 覆盖）");
        }
        else if (!exists)
        {
            warnings.add("扫描产物目录不存在（扫描时会自动创建，需父目录可写）: " + value);
        }
        else if (!writable)
        {
            warnings.add("扫描产物目录不可写: " + value);
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("value", value);
        node.put("exists", exists);
        node.put("writable", writable);
        return node;
    }

    /** 回收站根：trashPath 留空时回显实际生效的 {@code <rootPath>/_媒体站/trash} */
    private Map<String, Object> trashNode(List<String> warnings)
    {
        String configured = normalizePath(mediaProperties.getTrashPath());
        if (!configured.isEmpty())
        {
            return dirNode(configured, "trashPath", "回收站根", false, true, warnings);
        }
        String effective = "";
        try
        {
            effective = normalizePath(mediaFileService.trashRoot().toString());
        }
        catch (RuntimeException e)
        {
            // 媒体根也没配好：由 rootPath 那条 warning 表达原因，这里只回空节点
            effective = "";
        }
        if (effective.isEmpty())
        {
            warnings.add("回收站根无法确定：qtools.media.rootPath 未配置");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("value", "");
            node.put("exists", false);
            node.put("readable", false);
            node.put("writable", false);
            return node;
        }
        return dirNode(effective, "trashPath", "回收站根", false, true, warnings);
    }

    /** 一级子项数量（不递归：媒体根有上万文件，自检不能全量扫描） */
    private static long countChildren(Path dir)
    {
        if (!probe(dir, Files::isDirectory))
        {
            return 0L;
        }
        try (Stream<Path> children = Files.list(dir))
        {
            return children.count();
        }
        catch (IOException | RuntimeException e)
        {
            return 0L;
        }
    }

    /** 配置值 → Path；空值或非法路径返回 null（不抛 InvalidPathException） */
    private static Path toPath(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }
        try
        {
            return Paths.get(value);
        }
        catch (InvalidPathException e)
        {
            return null;
        }
    }

    private static boolean probe(Path path, Predicate<Path> check)
    {
        if (path == null)
        {
            return false;
        }
        try
        {
            return check.test(path);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** 统一成正斜杠并去掉尾部斜杠（与媒体接口里的相对路径风格一致） */
    private static String normalizePath(String raw)
    {
        if (raw == null)
        {
            return "";
        }
        String text = raw.trim().replace('\\', '/');
        while (text.length() > 3 && text.endsWith("/"))
        {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** qtools.media.scanOutPath → QTOOLS_MEDIA_SCANOUTPATH（与 Spring 宽松绑定一致） */
    private static String envNameOf(String key)
    {
        return ("qtools.media." + key).replace('.', '_').toUpperCase();
    }

    private static String message(Throwable e)
    {
        String text = e.getMessage();
        return text == null || text.isEmpty() ? e.getClass().getSimpleName() : text;
    }

    @Override
    public List<Map<String, Object>> people()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : mediaAdminMapper.selectPersonStats())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", longOf(row.get("id")));
            node.put("name", row.get("name"));
            node.put("albumCount", longOf(row.get("albums")));
            node.put("photoCount", longOf(row.get("photos")));
            out.add(node);
        }
        return out;
    }

    @Override
    public Map<String, Object> updateAlbumPerson(String albumId, Long personId)
    {
        MediaAlbum album = requireAlbum(albumId);
        if (personId != null)
        {
            boolean exists = false;
            for (MediaPerson person : mediaPersonMapper.selectPersonList())
            {
                if (person.getId().equals(personId))
                {
                    exists = true;
                    break;
                }
            }
            if (!exists)
            {
                throw MediaException.notFound("person not found: " + personId);
            }
        }
        int updated = mediaAdminMapper.updateAlbumPerson(albumId, personId);
        mediaIndexService.invalidateSnapshot();
        log.info("管理端改相册人物 album={} personId={} rows={}", albumId, personId, updated);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", updated);
        out.put("id", albumId);
        out.put("personId", personId);
        return out;
    }

    // ------------------------------------------------------------------ 行整形

    private Map<String, Object> videoRow(MediaVideo video)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", video.getId());
        node.put("title", video.getTitle());
        node.put("folder", video.getFolder());
        node.put("relPath", video.getRelPath());
        node.put("size", video.getSize());
        node.put("dur", video.getDur());
        node.put("w", video.getW());
        node.put("h", video.getH());
        node.put("vcodec", video.getVcodec());
        node.put("browserOk", video.getBrowserOk() != null && video.getBrowserOk() != 0);
        node.put("poster", video.getPosterPath());
        node.put("hidden", video.getHidden() != null && video.getHidden() != 0);
        node.put("mtime", formatDate(video.getMtime()));
        // 缺失文件（P2-D）：管理端列表据此标红点；用户端默认仍带出，由前端自行过滤
        node.put("missing", video.getMissing() != null && video.getMissing() != 0);
        node.put("lastSeen", formatDate(video.getLastSeen()));
        return node;
    }

    private Map<String, Object> albumRow(MediaAlbum album, Map<Long, String> personNames)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", album.getId());
        node.put("title", album.getTitle());
        node.put("dir", album.getDir());
        node.put("personId", album.getPersonId());
        node.put("person", album.getPersonId() == null ? null : personNames.get(album.getPersonId()));
        node.put("photoCount", album.getPhotoCount());
        node.put("bytes", album.getBytes());
        node.put("hidden", album.getHidden() != null && album.getHidden() != 0);
        node.put("cover", album.getCoverPath());
        return node;
    }

    private Map<String, Object> photoRow(MediaPhoto photo)
    {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", photo.getId());
        node.put("seq", photo.getSeq());
        node.put("relPath", photo.getRelPath());
        node.put("bytes", photo.getBytes());
        node.put("isBig", photo.getIsBig() != null && photo.getIsBig() != 0);
        node.put("hidden", photo.getHidden() != null && photo.getHidden() != 0);
        node.put("thumbUrl", thumbUrl(photo.getAlbumId(), photo.getSeq()));
        // 缺失文件（P2-D）：前端看到 missing=true 就不去请求缩略图，只画「缺失」角标
        node.put("missing", photo.getMissing() != null && photo.getMissing() != 0);
        node.put("lastSeen", formatDate(photo.getLastSeen()));
        // 内容哈希：前端可以拼 /api/media/thumb/p/<albumId>/<hash>（服务端两种写法都认）
        node.put("contentHash", photo.getContentHash());
        return node;
    }

    private static String thumbUrl(String albumId, Integer seq)
    {
        if (albumId == null || seq == null)
        {
            return null;
        }
        return "/api/media/thumb/p/" + albumId + "/" + String.format("%04d", seq);
    }

    // ------------------------------------------------------------------ 工具

    private MediaVideo requireVideo(String id)
    {
        MediaVideo video = StringUtils.isEmpty(id) ? null : mediaAdminMapper.selectVideoAnyById(id);
        if (video == null)
        {
            throw MediaException.notFound("video not found: " + id);
        }
        return video;
    }

    private MediaAlbum requireAlbum(String id)
    {
        MediaAlbum album = StringUtils.isEmpty(id) ? null : mediaAdminMapper.selectAlbumAnyById(id);
        if (album == null)
        {
            throw MediaException.notFound("album not found: " + id);
        }
        return album;
    }

    /**
     * photo 定位：优先 albumId+seq，其次纯数字 photoId，再次 {@code <albumId>:<seq>}
     */
    private MediaPhoto resolvePhoto(String id, String albumId, Integer seq)
    {
        if (StringUtils.isNotEmpty(albumId) && seq != null)
        {
            MediaPhoto photo = mediaAdminMapper.selectPhotoByAlbumSeq(albumId, seq);
            if (photo == null)
            {
                throw MediaException.notFound("photo not found: " + albumId + ":" + seq);
            }
            return photo;
        }
        String trimmed = StringUtils.isEmpty(id) ? null : id.trim();
        if (trimmed == null)
        {
            throw MediaException.badRequest("missing photo id");
        }
        if (trimmed.matches("^\\d{1,19}$"))
        {
            MediaPhoto photo = mediaAdminMapper.selectPhotoAnyById(Long.valueOf(trimmed));
            if (photo == null)
            {
                throw MediaException.notFound("photo not found: " + trimmed);
            }
            return photo;
        }
        int cut = trimmed.indexOf(':');
        if (cut > 0)
        {
            String album = trimmed.substring(0, cut);
            String seqText = trimmed.substring(cut + 1);
            if (seqText.matches("^\\d{1,6}$"))
            {
                MediaPhoto photo = mediaAdminMapper.selectPhotoByAlbumSeq(album, Integer.valueOf(seqText));
                if (photo == null)
                {
                    throw MediaException.notFound("photo not found: " + trimmed);
                }
                return photo;
            }
        }
        throw MediaException.badRequest("photo 需要 photoId 或 albumId + seq");
    }

    /** 软删/恢复后回写 hidden 位 */
    private int setHidden(String kind, String storedId, int flag)
    {
        if (KIND_VIDEO.equals(kind))
        {
            return mediaAdminMapper.updateVideoHidden(storedId, flag);
        }
        if (KIND_ALBUM.equals(kind))
        {
            return mediaAdminMapper.updateAlbumHidden(storedId, flag);
        }
        return mediaAdminMapper.updatePhotoHidden(Long.valueOf(storedId), flag);
    }

    /** 回收站目标名：{@code <id>__<原名>}，已存在时加时间戳后缀，绝不覆盖 */
    private Path uniqueTarget(Path dir, String id, String baseName)
    {
        Path target = dir.resolve(id + TRASH_SEP + baseName);
        if (!Files.exists(target))
        {
            return target;
        }
        String stamp = LocalDateTime.now().format(SUFFIX_FORMAT);
        target = dir.resolve(id + TRASH_SEP + stamp + TRASH_SEP + baseName);
        int n = 1;
        while (Files.exists(target))
        {
            target = dir.resolve(id + TRASH_SEP + stamp + "_" + n + TRASH_SEP + baseName);
            n++;
        }
        return target;
    }

    /**
     * 移动；跨卷失败时退化为「复制 + 删源」
     *
     * <p>{@link Files#move} 同卷是改名，跨卷（多库根常是另一块盘）直接抛异常。这里补一层：
     * 复制完整成功之后才删源；复制中途失败就清掉残留目标再抛出，绝不留半份文件、
     * 也绝不在复制失败时先删掉源。</p>
     */
    private static void moveOrCopy(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target);
            return;
        }
        catch (IOException e)
        {
            log.warn("移动失败，改走复制+删源（多为跨盘）：{} -> {}（{}）", source, target, e.getMessage());
        }
        try
        {
            copyRecursively(source, target);
        }
        catch (IOException e)
        {
            // 复制半途失败：清掉残留目标，源保持原样
            try
            {
                deleteRecursively(target);
            }
            catch (IOException ignored)
            {
                log.warn("清理复制残留失败：{}", target);
            }
            throw e;
        }
        deleteRecursively(source);
    }

    /** 递归复制（相册软删搬的是整个目录）；目标已存在即失败，绝不覆盖 */
    private static void copyRecursively(Path source, Path target) throws IOException
    {
        Files.walkFileTree(source, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 相册所属库根：相册表没有 {@code root_id} 列，按册内图片的 {@code root_id} 推断
     *
     * @return {@code media_root.id}；册内图片全为 NULL（存量数据）或列不存在时返回 null = 默认根
     */
    private Integer albumRootId(String albumId)
    {
        if (StringUtils.isEmpty(albumId))
        {
            return null;
        }
        try
        {
            return mediaAdminMapper.selectAlbumRootId(albumId);
        }
        catch (RuntimeException e)
        {
            // root_id 列还没加（没跑 media_portability.sql）：按默认根处理
            return null;
        }
    }

    /**
     * 回收站里该 id 的所有条目，按「软删先后」升序返回（最后一个是最近一次）
     *
     * <p>坑：<b>不能按文件名字典序判断新旧</b>。文件名形如 {@code <id>__<原名>}（首次软删）或
     * {@code <id>__<yyyyMMddHHmmss>__<原名>}（同 id 再次软删），而原名常是中文/emoji：
     * 字典序里 '2' 排在汉字前面，带时间戳的新条目会被误判成旧的，恢复时就会把旧文件
     * （甚至同名占位文件）搬回原位。解析统一交给 {@link #parseTrashEntry}，没有时间戳的一律视为最早。</p>
     */
    private List<TrashEntry> listTrashEntries(String kind, String id)
    {
        List<TrashEntry> found = new ArrayList<>();
        for (TrashEntry entry : scanTrash(kind))
        {
            if (id.equals(entry.id()))
            {
                found.add(entry);
            }
        }
        if (found.size() > 1)
        {
            log.warn("回收站里存在 {} 个同 id 条目，将取最近一次：{}", found.size(), found);
        }
        return found;
    }

    /** 某类（或全部）回收站条目，按软删先后升序；newestFirst 时倒序（列表展示用） */
    private List<TrashEntry> scanTrash(String kindFilter, boolean newestFirst)
    {
        List<TrashEntry> found = new ArrayList<>();
        for (String kind : "all".equals(kindFilter) ? TRASH_KINDS : List.of(kindFilter))
        {
            found.addAll(scanTrash(kind));
        }
        found.sort(TRASH_ORDER);
        if (newestFirst)
        {
            found.sort(TRASH_ORDER.reversed());
        }
        return found;
    }

    /**
     * 某一类的全部回收站条目（含各 {@code root_id} 桶），按软删先后升序
     *
     * <p>两种层级并存：老结构 {@code trash/<kind>/<id>__<原名>}（root_id 为 NULL）与
     * 多库根结构 {@code trash/<kind>/<rootId>/<id>__<原名>}。桶目录是纯数字名，而条目名
     * 一定含 {@code __}，所以 {@link #isRootBucket} 不会把条目误判成桶。</p>
     */
    private List<TrashEntry> scanTrash(String kind)
    {
        Path dir = mediaFileService.resolveTrashDir(kind);
        List<TrashEntry> found = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir))
        {
            for (Path path : stream.toList())
            {
                if (!isRootBucket(path))
                {
                    found.add(parseTrashEntry(kind, path, null));
                    continue;
                }
                Integer bucket = Integer.valueOf(path.getFileName().toString());
                try (Stream<Path> inner = Files.list(path))
                {
                    for (Path entry : inner.toList())
                    {
                        found.add(parseTrashEntry(kind, entry, bucket));
                    }
                }
            }
        }
        catch (IOException e)
        {
            throw new MediaException(500, "回收站不可读: " + dir);
        }
        found.sort(TRASH_ORDER);
        return found;
    }

    /** 回收站里的库根桶：目录名是纯数字（{@code trash/<kind>/<rootId>}） */
    private static boolean isRootBucket(Path path)
    {
        return Files.isDirectory(path) && ROOT_BUCKET.matcher(path.getFileName().toString()).matches();
    }

    /**
     * 解析回收站条目 —— 软删 / 恢复 / 列回收站<b>唯一</b>的解析入口
     *
     * <p>文件名两种形态：{@code <id>__<原名>}（首次软删）与
     * {@code <id>__<yyyyMMddHHmmss>[_n]__<原名>}（同 id 再次软删，由
     * {@link #uniqueTarget} 造）。restore 用 stamp 选最近一次软删，列表接口用同一个 stamp
     * 算 deletedAt，两处必须是同一个时间戳来源。</p>
     */
    private static TrashEntry parseTrashEntry(String kind, Path path, Integer rootId)
    {
        String fileName = path.getFileName().toString();
        String id = null;
        String stamp = null;
        String name = fileName;
        int cut = fileName.indexOf(TRASH_SEP);
        if (cut > 0)
        {
            id = fileName.substring(0, cut);
            String rest = fileName.substring(cut + TRASH_SEP.length());
            int end = rest.indexOf(TRASH_SEP);
            String head = end < 0 ? rest : rest.substring(0, end);
            if (TRASH_STAMP.matcher(head).matches())
            {
                stamp = head;
                name = end < 0 ? rest : rest.substring(end + TRASH_SEP.length());
            }
            else
            {
                name = rest;
            }
        }
        boolean isDir = Files.isDirectory(path);
        long bytes = isDir ? dirBytes(path) : fileBytes(path);
        // 首次软删的文件名里没有时间戳 → 退回文件时间（排序仍按 TRASH_ORDER 视为最早）
        Date deletedAt = stamp == null ? fileTime(path) : parseStamp(stamp);
        return new TrashEntry(kind, id, name, stamp, path, isDir, bytes, deletedAt, rootId);
    }

    /**
     * 前端回传的 trashRel（相对媒体根，如 {@code _媒体站/trash/photo/463__x.jpg}）→ 真实路径
     *
     * <p>彻底删除是唯一会真删文件的入口，所以这里按「绝对路径 / 盘符 / UNC / .. 逃逸 /
     * 符号链接逃逸」逐层拒绝：必须落在 {@code trash/<kind>} 之下，且 realpath 之后仍在其中。</p>
     */
    private Path resolveTrashTarget(String kind, String rel)
    {
        if (rel == null || rel.isEmpty() || rel.indexOf('\0') >= 0)
        {
            throw MediaException.forbidden();
        }
        char first = rel.charAt(0);
        if (first == '/' || first == '\\')
        {
            throw MediaException.forbidden();
        }
        if (rel.length() > 1 && rel.charAt(1) == ':' && Character.isLetter(first))
        {
            throw MediaException.forbidden();
        }
        Path trashRoot = realTrashRoot();
        Path target;
        try
        {
            target = mediaFileService.mediaRoot().resolve(rel).normalize();
        }
        catch (InvalidPathException e)
        {
            throw MediaException.forbidden();
        }
        if (!target.startsWith(trashRoot))
        {
            // 含任何 .. 逃出 trash 的写法都在这里被拦下
            throw MediaException.forbidden();
        }
        return requireTrashObject(kind, target);
    }

    /** 目标必须存在、realpath 之后仍落在 {@code trash/<kind>} 之下、且不是 kind 目录本身 */
    private Path requireTrashObject(String kind, Path target)
    {
        Path kindDir = realTrashRoot().resolve(kind);
        Path real;
        try
        {
            real = target.toRealPath();
        }
        catch (IOException e)
        {
            throw MediaException.notFound("回收站中没有该对象: " + target.getFileName());
        }
        if (!real.startsWith(kindDir) || real.equals(kindDir) || isRootBucket(real))
        {
            // 桶目录（trash/<kind>/<rootId>）本身不能被整桶彻底删：那会一次抹掉多个对象
            throw MediaException.forbidden();
        }
        return real;
    }

    /** 回收站根 realpath（trashPath 配置本身已限制在 _媒体站 下） */
    private Path realTrashRoot()
    {
        Path trash = mediaFileService.trashRoot();
        try
        {
            return Files.exists(trash) ? trash.toRealPath() : trash;
        }
        catch (IOException e)
        {
            throw new MediaException(500, "回收站不可访问: " + trash);
        }
    }

    /** 彻底删除：文件直接删，目录递归删（回收站里的目录来自相册软删） */
    private static void deleteRecursively(Path target) throws IOException
    {
        if (!Files.isDirectory(target))
        {
            Files.delete(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
            {
                if (exc != null)
                {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 时间戳（yyyyMMddHHmmss[_n]）→ Date；解析失败返回 null（不阻塞列表） */
    private static Date parseStamp(String stamp)
    {
        try
        {
            LocalDateTime time = LocalDateTime.parse(stamp.substring(0, 14), SUFFIX_FORMAT);
            return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static Date fileTime(Path path)
    {
        try
        {
            return new Date(Files.getLastModifiedTime(path).toMillis());
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private static long fileBytes(Path path)
    {
        try
        {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    /** 目录递归字节数（相册软删会把整个物理目录搬进回收站） */
    private static long dirBytes(Path path)
    {
        if (!Files.isDirectory(path))
        {
            return fileBytes(path);
        }
        try (Stream<Path> stream = Files.walk(path))
        {
            return stream.filter(Files::isRegularFile).mapToLong(MediaAdminServiceImpl::fileBytes).sum();
        }
        catch (IOException e)
        {
            log.warn("回收站目录字节统计失败 {}: {}", path, e.getMessage());
            return 0L;
        }
    }

    /** kind 过滤：空/all/* → all，其余必须是 video / album / photo */
    private static String normalizeTrashKind(String kind)
    {
        String value = trimToNull(kind);
        if (value == null)
        {
            return "all";
        }
        String lower = value.toLowerCase();
        if ("all".equals(lower) || "*".equals(lower))
        {
            return "all";
        }
        requireKind(lower);
        return lower;
    }

    private long trashBytes()
    {
        Path trash = mediaFileService.trashRoot();
        if (!Files.isDirectory(trash))
        {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(trash))
        {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try
                {
                    return Files.size(path);
                }
                catch (IOException e)
                {
                    return 0L;
                }
            }).sum();
        }
        catch (IOException e)
        {
            log.warn("回收站字节统计失败: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * 回收站对象数（一次软删 = 一个对象）
     *
     * <p>层级两种都算：{@code trash/<kind>/<条目>} 与 {@code trash/<kind>/<rootId>/<条目>}；
     * 桶目录本身不是对象，所以进桶再数一层。</p>
     */
    private long trashCount()
    {
        Path trash = mediaFileService.trashRoot();
        if (!Files.isDirectory(trash))
        {
            return 0L;
        }
        long count = 0L;
        try (Stream<Path> kinds = Files.list(trash))
        {
            for (Path kindDir : kinds.filter(Files::isDirectory).toList())
            {
                try (Stream<Path> entries = Files.list(kindDir))
                {
                    for (Path child : entries.toList())
                    {
                        if (!isRootBucket(child))
                        {
                            count++;
                            continue;
                        }
                        try (Stream<Path> inner = Files.list(child))
                        {
                            count += inner.count();
                        }
                    }
                }
            }
        }
        catch (IOException e)
        {
            log.warn("回收站对象统计失败: {}", e.getMessage());
        }
        return count;
    }

    private Map<Long, String> loadPersonNames()
    {
        Map<Long, String> names = new LinkedHashMap<>();
        for (MediaPerson person : mediaPersonMapper.selectPersonList())
        {
            names.put(person.getId(), person.getName());
        }
        return names;
    }

    private static void requireKind(String kind)
    {
        if (!KIND_VIDEO.equals(kind) && !KIND_ALBUM.equals(kind) && !KIND_PHOTO.equals(kind))
        {
            throw MediaException.badRequest("kind 只能是 video / album / photo");
        }
    }

    /** hidden 过滤参数：all/空 = 不过滤，true/1 = 已隐藏，false/0 = 未隐藏 */
    private static String normalizeHidden(String hidden)
    {
        if (StringUtils.isEmpty(hidden))
        {
            return null;
        }
        String value = hidden.trim().toLowerCase();
        if ("all".equals(value) || "*".equals(value))
        {
            return null;
        }
        if ("1".equals(value) || "true".equals(value))
        {
            return "1";
        }
        if ("0".equals(value) || "false".equals(value))
        {
            return "0";
        }
        throw MediaException.badRequest("hidden 只能是 true/false/all");
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long longOf(Object value)
    {
        if (value == null)
        {
            return 0L;
        }
        if (value instanceof Number number)
        {
            return number.longValue();
        }
        try
        {
            return Long.parseLong(value.toString());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private static String formatDate(Date date)
    {
        return date == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, date);
    }

    /** 与 BaseController#getDataTable 输出一致，但 total 由本层在行整形前取好 */
    private static TableDataInfo dataTable(List<?> rows, long total)
    {
        TableDataInfo table = new TableDataInfo(rows, total);
        table.setCode(200);
        table.setMsg("查询成功");
        return table;
    }

    /**
     * 回收站条目（磁盘为准）：{@code trash/<kind>/<id>__<原名>}
     *
     * @param id        文件名校验出的对象 id（文件名没有 {@code __} 时为 null，按孤儿处理）
     * @param name      原名（文件名里 id/时间戳之后的部分）
     * @param stamp     文件名内嵌的软删时间戳；首次软删没有时间戳时为 null（视为最早）
     * @param deletedAt 展示用的删除时间：有时间戳取时间戳，否则取文件时间
     * @param rootId    条目所在桶对应的库根 id；null = 老结构（root_id 为 NULL 的存量条目）
     */
    private record TrashEntry(String kind, String id, String name, String stamp, Path path, boolean isDir,
            long bytes, Date deletedAt, Integer rootId)
    {
    }
}
