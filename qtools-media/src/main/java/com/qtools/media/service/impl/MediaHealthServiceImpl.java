package com.qtools.media.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaHealthMapper;
import com.qtools.media.service.IMediaHealthService;

/**
 * 库健康检查实现（P6.06）
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li><b>只读优先</b>：除了「计数重算」和「删目录不存在的空壳」，其余一律只报数——体检不该顺手改数据；</li>
 *   <li><b>目录判断在 Java 里做</b>：SQL 判断不了「这个 dir_key 在磁盘上还在不在」，
 *       而 multi-root 下同一相对路径要按每个根试一遍；</li>
 *   <li><b>身份两列可能还没迁移</b>：查不到列就报 {@code ok=null} + 提示迁移，绝不让整张体检表 500。</li>
 * </ul>
 *
 * @author qtools
 */
@Service
public class MediaHealthServiceImpl implements IMediaHealthService
{
    private static final Logger log = LoggerFactory.getLogger(MediaHealthServiceImpl.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 样例最多给几条（面板只展示前几行，避免把上千行拖回前端） */
    private static final int SAMPLE_LIMIT = 5;

    @Autowired
    private MediaHealthMapper mediaHealthMapper;

    @Autowired
    private MediaProperties mediaProperties;

    @Override
    public Map<String, Object> report()
    {
        List<Map<String, Object>> checks = new ArrayList<>();
        int problems = 0;

        long counterMismatch = mediaHealthMapper.countAlbumCounterMismatch();
        Map<String, Object> counterCheck = check("albumCounters", "相册计数与实际行数", counterMismatch,
                counterMismatch == 0 ? "全部一致" : counterMismatch + " 本声明张数 ≠ 实际行数",
                counterMismatch == 0 ? null : "albumCounters");
        if (counterMismatch > 0)
        {
            counterCheck.put("samples", mediaHealthMapper.selectCounterMismatchRows(SAMPLE_LIMIT));
            problems++;
        }
        checks.add(counterCheck);

        // 身份两列：老库没迁移时这一项标「未迁移」，不参与 problems 计数
        Map<String, Object> identityCheck = identityCheck();
        if (Boolean.FALSE.equals(identityCheck.get("ok")))
        {
            problems++;
        }
        checks.add(identityCheck);

        List<Map<String, Object>> emptyAlbums = mediaHealthMapper.selectEmptyAlbumList();
        List<String> emptyWithMissingDir = new ArrayList<>();
        Set<String> roots = roots();
        for (Map<String, Object> row : emptyAlbums)
        {
            if (!dirExists(stringOf(row.get("dirKey")), roots))
            {
                emptyWithMissingDir.add(stringOf(row.get("id")));
            }
        }
        Map<String, Object> emptyCheck = check("emptyAlbums", "空壳相册（0 图）", emptyAlbums.size(),
                emptyAlbums.isEmpty() ? "没有空壳相册"
                        : emptyAlbums.size() + " 本 0 图，其中 " + emptyWithMissingDir.size() + " 本目录已不存在（可一键清）",
                emptyWithMissingDir.isEmpty() ? null : "emptyAlbums");
        if (emptyWithMissingDir.size() < emptyAlbums.size() && !emptyAlbums.isEmpty())
        {
            List<Map<String, Object>> samples = new ArrayList<>();
            for (Map<String, Object> row : emptyAlbums)
            {
                if (samples.size() >= SAMPLE_LIMIT)
                {
                    break;
                }
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("id", row.get("id"));
                sample.put("title", row.get("title"));
                sample.put("dirExists", dirExists(stringOf(row.get("dirKey")), roots));
                samples.add(sample);
            }
            emptyCheck.put("samples", samples);
        }
        if (!emptyAlbums.isEmpty())
        {
            problems++;
        }
        checks.add(emptyCheck);

        List<Map<String, Object>> allDirs = mediaHealthMapper.selectAlbumDirList();
        List<String> dirMissing = new ArrayList<>();
        for (Map<String, Object> row : allDirs)
        {
            if (!dirExists(stringOf(row.get("dirKey")), roots))
            {
                dirMissing.add(stringOf(row.get("id")));
            }
        }
        Map<String, Object> dirCheck = check("albumDirMissing", "相册目录不存在", dirMissing.size(),
                dirMissing.isEmpty() ? allDirs.size() + " 本目录都在"
                        : dirMissing.size() + " 本目录在磁盘上找不到：" + String.join(", ", dirMissing.subList(0, Math.min(5, dirMissing.size()))),
                null);
        if (!dirMissing.isEmpty())
        {
            problems++;
        }
        checks.add(dirCheck);

        long missingPhotos = mediaHealthMapper.countMissingPhotos();
        Map<String, Object> photoCheck = check("missingPhotos", "图片缺失标记", missingPhotos,
                missingPhotos == 0 ? "没有缺失行" : missingPhotos + " 行文件不在磁盘上", missingPhotos == 0 ? null : "missing");
        if (missingPhotos > 0)
        {
            problems++;
        }
        checks.add(photoCheck);

        long missingVideos = mediaHealthMapper.countMissingVideos();
        Map<String, Object> videoCheck = check("missingVideos", "视频缺失标记", missingVideos,
                missingVideos == 0 ? "没有缺失行" : missingVideos + " 行文件不在磁盘上", missingVideos == 0 ? null : "missing");
        if (missingVideos > 0)
        {
            problems++;
        }
        checks.add(videoCheck);

        long orphanPhotos = mediaHealthMapper.countOrphanPhotoRows();
        Map<String, Object> orphanCheck = check("orphanPhotoRows", "孤儿图片行", orphanPhotos,
                orphanPhotos == 0 ? "每张图都有相册" : orphanPhotos + " 行找不到所属相册", null);
        if (orphanPhotos > 0)
        {
            problems++;
        }
        checks.add(orphanCheck);

        long noCover = mediaHealthMapper.countAlbumsWithoutCover();
        Map<String, Object> coverCheck = check("albumsWithoutCover", "无封面相册", noCover,
                noCover == 0 ? "每本都有封面路径" : noCover + " 本没有封面路径（跑一次重建缩略图会补上）", null);
        if (noCover > 0)
        {
            problems++;
        }
        checks.add(coverCheck);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", LocalDateTime.now().format(TS));
        out.put("ok", problems == 0);
        out.put("problems", problems);
        out.put("checks", checks);
        return out;
    }

    @Override
    public Map<String, Object> fix(String kind)
    {
        if ("albumCounters".equals(kind))
        {
            int rows = mediaHealthMapper.recountAlbumCounters();
            log.info("库健康修复：重算相册计数，更新 {} 行", rows);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("fixed", rows);
            out.put("note", "已按 media_photo 实际行数重算 photo_count / bytes");
            return out;
        }
        if ("emptyAlbums".equals(kind))
        {
            // 只删「0 图 + 目录确实不存在」的：目录还在的空壳可能是盘暂时没挂上，删了会丢人工归类
            Set<String> roots = roots();
            List<String> toDelete = new ArrayList<>();
            int kept = 0;
            for (Map<String, Object> row : mediaHealthMapper.selectEmptyAlbumList())
            {
                if (dirExists(stringOf(row.get("dirKey")), roots))
                {
                    kept++;
                    continue;
                }
                toDelete.add(stringOf(row.get("id")));
            }
            int deleted = 0;
            if (!toDelete.isEmpty())
            {
                // 先清人物头像引用：否则删完相册，头像 URL 悬空、前端只剩字母占位
                mediaHealthMapper.clearAvatarRefsForAlbums(toDelete);
                mediaHealthMapper.deleteAlbumPersonByAlbumIds(toDelete);
                deleted = mediaHealthMapper.deleteAlbumsByIds(toDelete);
            }
            log.info("库健康修复：删除空壳相册 {} 本（目录仍在而保留 {} 本）", deleted, kept);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("fixed", deleted);
            out.put("kept", kept);
            out.put("note", kept > 0 ? "另有 " + kept + " 本 0 图但目录还在，已保留" : "删的都是目录已不存在的");
            return out;
        }
        throw MediaException.badRequest("未知的修复项：" + kind + "（只支持 albumCounters / emptyAlbums）");
    }

    /** 身份两项合成一条：没迁移（列不存在）时报 ok=null，不误导成「坏了」 */
    private Map<String, Object> identityCheck()
    {
        try
        {
            long missing = mediaHealthMapper.countAlbumIdentityMissing();
            long conflict = mediaHealthMapper.countAlbumIdentityConflict();
            Map<String, Object> item = check("albumIdentity", "相册身份（identity_key）", missing + conflict,
                    (missing == 0 && conflict == 0) ? "全部已回填、无重复"
                            : "未回填 " + missing + " 本 / 同目录重复 " + conflict + " 组",
                    null);
            return item;
        }
        catch (RuntimeException e)
        {
            Map<String, Object> item = check("albumIdentity", "相册身份（identity_key）", -1,
                    "库未迁移：先执行 doc/media_album_identity.sql", null);
            item.put("ok", null);
            item.put("count", null);
            return item;
        }
    }

    private static Map<String, Object> check(String key, String label, long count, String detail, String fix)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("count", count);
        item.put("ok", count == 0);
        item.put("detail", detail);
        if (fix != null)
        {
            item.put("fix", fix);
        }
        return item;
    }

    /** 所有媒体根（media_root.kind='root'）∪ 配置里的 rootPath，用来拼相册的绝对目录 */
    private Set<String> roots()
    {
        Set<String> roots = new LinkedHashSet<>();
        try
        {
            List<String> fromDb = mediaHealthMapper.selectRootPaths();
            if (fromDb != null)
            {
                for (String p : fromDb)
                {
                    if (p != null && !p.isBlank())
                    {
                        roots.add(p.trim());
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            log.debug("读 media_root 失败，退回配置的 rootPath：{}", e.getMessage());
        }
        String configured = mediaProperties.getRootPath();
        if (configured != null && !configured.isBlank())
        {
            roots.add(configured.trim());
        }
        return roots;
    }

    /** dir_key 是相对媒体根、以 '/' 分隔；空串表示根目录下的散图 */
    private static boolean dirExists(String dirKey, Set<String> roots)
    {
        if (roots.isEmpty())
        {
            return true;
        }
        String rel = dirKey == null ? "" : dirKey;
        for (String root : roots)
        {
            try
            {
                Path base = Paths.get(root);
                Path target = rel.isEmpty() ? base : base.resolve(rel);
                if (Files.isDirectory(target))
                {
                    return true;
                }
            }
            catch (RuntimeException e)
            {
                log.debug("目录判断失败 root={} rel={}：{}", root, rel, e.getMessage());
            }
        }
        return false;
    }

    private static String stringOf(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }
}
