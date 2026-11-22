package com.qtools.tool.web.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.qtools.media.domain.dto.MediaScanIngestRequest;
import com.qtools.media.service.IMediaScanIngestService;

import javax.sql.DataSource;

/**
 * 「保存到媒体站」的导入执行器（P6.116）
 *
 * <p>流程：把已下载的 mp4 / 图片拷进媒体库根目录 → 调用媒体站自己的扫描入库执行器写库
 * → 扫完回填媒体站的分级（{@code media_video.content_rating}）与人物（{@code media_video_actor}）。</p>
 *
 * <p>为什么不自己往媒体表里插行：媒体站的库根、rel_path、缩略图、索引快照是一整套约定，
 * 绕过扫描器插行会留下打不开的条目。这里只做「搬文件 + 让它自己扫 + 扫完补字段」。</p>
 *
 * @author qtools
 */
@Service
public class WebMediaImportService
{
    private static final Logger log = LoggerFactory.getLogger(WebMediaImportService.class);

    /** 服务端运行根，启动脚本以 -Dqtools.home 传入（缺省为便携布局） */
    private static final String HOME = System.getProperty("qtools.home", "E:/111/project/QTools");

    private static final String LIB_FOLDER = "每日大赛";

    /** 抓取素材落盘目录（下载 / 重签产物） */
    private static final String WEB_ROOT = HOME + "/data/media/web";

    /** 媒体库主根：取 qtools.media.rootPath，未配置时回退便携布局 */
    @Value("${qtools.media.rootPath:E:/111/视频}")
    private String libRoot;

    /** 缩略图产物根：取 qtools.media.thumbsPath，未配置时回退便携布局 */
    @Value("${qtools.media.thumbsPath:}")
    private String thumbsPathCfg;

    /** 缩略图子目录（p=图片 / v=视频封面），配置优先 */
    private Path thumbsDir(String kind)
    {
        if (thumbsPathCfg != null && !thumbsPathCfg.trim().isEmpty())
        {
            return Paths.get(thumbsPathCfg, kind);
        }
        return Paths.get(libRoot, "..", "project/QTools/data/media/thumbs/" + kind).normalize();
    }

    /** 下载 sidecar（ffmpeg 拉 HLS 的活儿交给它） */
    private static final String SIDECAR = "http://127.0.0.1:8765";

    private static final Long LIB_ROOT_ID = 1L;

    @Autowired
    private IMediaScanIngestService scanIngestService;

    @Autowired
    private DataSource dataSource;

    /** 按帖加锁：同一条帖子同时只跑一个导入（扫描任务在后端本就串行，不靠这把锁互斥） */
    private final Set<Long> busyPosts = ConcurrentHashMap.newKeySet();

    /**
     * 补段进度（内存态）：postId → {@code {seg, count, phase, at}}。
     *
     * <p>补段是后台线程里逐段等 sidecar 下载（每段几十秒、一整条 8~10 分钟），
     * 只活在这个 JVM 里就够：导入记录被 {@code /tool/web/download-records} 读到时会合并它，
     * 用户在「下载记录」里就能看到「补段 3/12」。重启后丢进度不影响任何数据。</p>
     */
    private final Map<Long, Map<String, Object>> segmentProgress = new ConcurrentHashMap<>();

    /** 补段进度快照；没有记录时返回 null */
    public Map<String, Object> segmentProgress(Long postId)
    {
        return postId == null ? null : segmentProgress.get(postId);
    }

    private void markSegment(Long postId, int seg, int count, String phase)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seg", seg);
        m.put("count", count);
        m.put("phase", phase);
        m.put("at", System.currentTimeMillis());
        segmentProgress.put(postId, m);
        log.info("补段进度 postId={} {}/{} phase={}", postId, seg, count, phase);
    }

    /**
     * 导入一条内容到媒体站
     *
     * @return {@code {ok, folder, videoFile, jobId, message}}
     */
    /**
     * 确保这条帖子的所有视频分段都已落在服务端（video.mp4 / v2.mp4 / …）。
     *
     * <p>多段帖子只有第 1 段是「保存到本地」时下好的，其余段要点过批量下载才有。
     * 入库时如果只拷第 1 段，媒体站就只多出 1 个视频（用户看到的就是"12 段只进 1 段"），
     * 所以这里先按段请 sidecar 用 ffmpeg 补齐，再交给后面的拷贝逻辑。</p>
     */
    private void ensureAllSegments(Long postId, Path srcDir)
    {
        int count;
        try
        {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer c = jdbc.queryForObject("select ifnull(video_count,1) from tool_web_post where id = ?",
                    Integer.class, postId);
            count = c == null ? 1 : Math.max(1, c);
        }
        catch (Exception e)
        {
            return;
        }
        long deadline = System.currentTimeMillis() + 12 * 60_000L;
        for (int seg = 1; seg < count; seg++)
        {
            Path f = srcDir.resolve("v" + (seg + 1) + ".mp4");
            if (Files.exists(f))
            {
                continue;
            }
            if (System.currentTimeMillis() > deadline)
            {
                log.warn("分段补齐超时 postId={} 已到第 {} 段", postId, seg + 1);
                markSegment(postId, seg + 1, count, "timeout");
                return;
            }
            markSegment(postId, seg + 1, count, "downloading");
            triggerSegmentDownload(postId, seg);
            long until = Math.min(deadline, System.currentTimeMillis() + 120_000L);
            while (!Files.exists(f) && System.currentTimeMillis() < until)
            {
                try
                {
                    Thread.sleep(2000);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            boolean ready = Files.exists(f);
            log.info("分段 {}/{} 就绪={} postId={}", seg + 1, count, ready, postId);
            markSegment(postId, seg + 1, count, ready ? "ready" : "missing");
        }
        markSegment(postId, count, count, "done");
    }

    /** 后台补齐剩余分段并追加入库（不阻塞保存请求） */
    private void startSegmentBackfill(Long postId, Path srcDir, String safe, Path targetDir)
    {
        int count;
        try
        {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer c = jdbc.queryForObject("select ifnull(video_count,1) from tool_web_post where id = ?",
                    Integer.class, postId);
            count = c == null ? 1 : Math.max(1, c);
        }
        catch (Exception e)
        {
            return;
        }
        if (count <= 1)
        {
            return;
        }
        markSegment(postId, 1, count, "waiting");
        Thread t = new Thread(() -> {
            try
            {
                ensureAllSegments(postId, srcDir);
                int copied = 0;
                for (int seg = 1; seg < count; seg++)
                {
                    Path extra = srcDir.resolve("v" + (seg + 1) + ".mp4");
                    if (Files.exists(extra))
                    {
                        Path segTarget = targetDir.resolve(safe + "-" + (seg + 1) + ".mp4");
                        if (!alreadyCopied(extra, segTarget))
                        {
                            Files.copy(extra, segTarget, StandardCopyOption.REPLACE_EXISTING);
                        }
                        copied += 1;
                    }
                }
                if (copied > 0)
                {
                    MediaScanIngestRequest req = new MediaScanIngestRequest();
                    req.setRootId(LIB_ROOT_ID);
                    req.setWithVideo(Boolean.TRUE);
                    req.setWithPhoto(Boolean.FALSE);
                    req.setPipeline(Boolean.TRUE);
                    for (int i = 0; i < 30; i += 1)
                    {
                        try
                        {
                            scanIngestService.start(req);
                            log.info("分段补录完成 postId={} 追加 {} 段，已触发扫描", postId, copied);
                            markSegment(postId, count, count, "done");
                            return;
                        }
                        catch (RuntimeException e)
                        {
                            Thread.sleep(10000);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                log.warn("分段后台补录失败 postId={} err={}", postId, e.toString());
                markSegment(postId, count, count, "failed");
            }
        }, "seg-backfill-" + postId);
        t.setDaemon(true);
        t.start();
    }

    /** 请 sidecar 下载第 index 段（index 从 0 开始，0 就是 video.mp4） */
    private void triggerSegmentDownload(Long postId, int index)
    {
        try
        {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(SIDECAR + "/download"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers
                            .ofString("{\"id\":" + postId + ",\"index\":" + index + "}"))
                    .build();
            http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        }
        catch (Exception e)
        {
            log.warn("触发分段下载失败 postId={} index={} err={}", postId, index, e.toString());
        }
    }

    public Map<String, Object> importPost(Long postId, Long recordId, String sourceId, String title, String rating,
            List<String[]> persons, String album, String studio, String kind, String sourceTags,
            String imageFile)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!busyPosts.add(postId))
        {
            out.put("ok", false);
            out.put("message", "这条帖子正在导入中，稍后再试");
            return out;
        }
        try
        {
            String safe = safeName(title == null ? ("post-" + postId) : title);
            Path srcDir = Paths.get(WEB_ROOT, String.valueOf(sourceId));
            if (!Files.isDirectory(srcDir))
            {
                out.put("ok", false);
                out.put("message", "服务端还没有这条的文件，先点「保存到本地」准备一下");
                return out;
            }
            // 视频放 <库根>/每日大赛/<标题>.mp4；图片放 <库根>/每日大赛/<标题>/ 下
            Path targetDir = Paths.get(libRoot, LIB_FOLDER);
            Files.createDirectories(targetDir);
            boolean wantVideo = !"images".equals(kind);
            boolean wantImages = "images".equals(kind);
            if (wantImages && (album == null || album.isBlank()))
            {
                out.put("ok", false);
                out.put("message", "保存图片必须指定相册");
                return out;
            }
            Path videoSrc = srcDir.resolve("video.mp4");
            Path videoTarget = targetDir.resolve(safe + ".mp4");
            if (wantVideo)
            {
                // 多段帖子：先把已存在的段拷进去，其余段交给后台线程补齐后再扫一次。
                // 直接在请求里等 11 段（每段几十秒）会把接口挂十几分钟，前端早就超时了。
                startSegmentBackfill(postId, srcDir, safe, targetDir);
            }
            if (wantVideo)
            {
                if (!Files.exists(videoSrc))
                {
                    out.put("ok", false);
                    out.put("message", "服务端还没有这条的视频文件");
                    return out;
                }
                if (!alreadyCopied(videoSrc, videoTarget))
                {
                    Files.copy(videoSrc, videoTarget, StandardCopyOption.REPLACE_EXISTING);
                }
                // 多段视频：video.mp4 只是第一段，v2.mp4 / v3.mp4 … 也要一起搬进去，
                // 否则 12 段只入库 1 段（用户看到的就是"管理端只有一条视频"）
                java.util.List<Integer> copied = new java.util.ArrayList<>();
                copied.add(1);
                for (int seg = 2; ; seg++)
                {
                    Path extra = srcDir.resolve("v" + seg + ".mp4");
                    if (!Files.exists(extra))
                    {
                        break;
                    }
                    Path segTarget = targetDir.resolve(safe + "-" + seg + ".mp4");
                    if (!alreadyCopied(extra, segTarget))
                    {
                        Files.copy(extra, segTarget, StandardCopyOption.REPLACE_EXISTING);
                    }
                    copied.add(seg);
                }
                out.put("videoSegments", copied.size());
            }

            // 图集：源站抓下来的图片直接躺在 <web>/<源站id>/p1.jpg、p2.jpg…
            // 拷到 <库根>/每日大赛/<标题>/ 下，扫描器会把同一目录归成一本相册
            int imageCount = 0;
            if (wantImages)
            try (var stream = Files.list(srcDir))
            {
                List<Path> pics = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jpg"))
                        // 「只存当前这张」：只搬选中的那个文件
                        .filter(p -> imageFile == null || imageFile.isEmpty()
                                || p.getFileName().toString().equalsIgnoreCase(imageFile))
                        .toList();
                if (!pics.isEmpty())
                {
                    // 图片按「相册名」建目录：媒体站按 <库根>/<分类>/<相册> 归册
                    Path imgDir = targetDir.resolve(safeName(album));
                    Files.createDirectories(imgDir);
                    for (Path p : pics)
                    {
                        Files.copy(p, imgDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        imageCount += 1;
                    }
                }
            }

            if (wantImages && imageCount == 0)
            {
                out.put("ok", false);
                out.put("message", "服务端还没有这条的图集（先打开一次详情让它抓取，再保存）");
                return out;
            }
            MediaScanIngestRequest req = new MediaScanIngestRequest();
            req.setRootId(LIB_ROOT_ID);
            // 按类型只扫对应资源，避免"存图片"时顺手又去处理视频
            req.setWithVideo(Boolean.valueOf(wantVideo));
            req.setWithPhoto(Boolean.valueOf(wantImages));
            req.setPipeline(Boolean.TRUE);
            // 媒体站同一时刻只允许一个扫描任务；撞上了就等它跑完再起，
            // 否则这次导入会直接失败（表现为"分级/人物没回填、封面没生成"）
            String jobId = null;
            for (int attempt = 0; attempt < 30 && jobId == null; attempt += 1)
            {
                try
                {
                    Map<String, Object> job = scanIngestService.start(req);
                    jobId = String.valueOf(job.get("jobId"));
                }
                catch (RuntimeException e)
                {
                    String msg = String.valueOf(e.getMessage());
                    if (msg.contains("已有扫描入库任务") || msg.contains("在运行"))
                    {
                        log.info("扫描任务占用中，等待后重试（第 {} 次）", attempt + 1);
                        try
                        {
                            Thread.sleep(10000);
                        }
                        catch (InterruptedException ie)
                        {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    throw e;
                }
            }
            if (jobId == null)
            {
                out.put("ok", false);
                out.put("message", "扫描任务长时间占用，稍后再试");
                return out;
            }

            // 扫描是异步的：起个后台线程等它跑完，再回填分级与人物
            final int imgs = imageCount;
            final String videoRel = wantVideo ? safe + ".mp4" : null;
            final String finalJobId = jobId;
            Thread t = new Thread(() -> awaitAndTag(finalJobId, videoRel, safeName(album == null ? safe : album),
                    rating, persons, studio, sourceTags, recordId),
                    "web-media-import-" + finalJobId);
            t.setDaemon(true);
            t.start();

            out.put("ok", true);
            out.put("jobId", jobId);
            out.put("folder", LIB_FOLDER);
            out.put("images", imgs);
            out.put("message", "已放入媒体库，正在扫描入库");
            return out;
        }
        catch (IOException e)
        {
            log.warn("导入媒体站失败 postId={} err={}", postId, e.toString());
            out.put("ok", false);
            out.put("message", "文件拷贝失败：" + e.getMessage());
            return out;
        }
        catch (RuntimeException e)
        {
            log.warn("触发扫描失败 postId={} err={}", postId, e.toString());
            out.put("ok", false);
            out.put("message", "触发扫描失败：" + e.getMessage());
            return out;
        }
        finally
        {
            busyPosts.remove(postId);
        }
    }

    /** 等扫描结束，然后给新入库的视频补分级 / 人物 */
    private void awaitAndTag(String jobId, String videoRel, String albumDir, String rating, List<String[]> persons,
            String studio, String sourceTags, Long recordId)
    {
        try
        {
            for (int i = 0; i < 300; i += 1)
            {
                Thread.sleep(2000);
                try
                {
                    Map<String, Object> job = scanIngestService.getJob(jobId, 20);
                    String status = String.valueOf(job.get("status"));
                    if ("running".equals(status) || "queued".equals(status))
                    {
                        continue;
                    }
                    log.info("媒体站扫描结束 job={} status={}", jobId, status);
                    break;
                }
                catch (RuntimeException e)
                {
                    // 任务查不到（进程重启/内存任务过期）：等到没有活动任务为止再回填
                    if (scanIngestService.active() == null)
                    {
                        break;
                    }
                }
            }
            // 扫描刚结束，媒体行的封面/缩略图是异步生成的，给它一点时间
            Thread.sleep(3000);
            String firstPerson = persons == null || persons.isEmpty() ? null : persons.get(0)[0];
            boolean tagged;
            if (videoRel != null)
            {
                tagged = applyTags(videoRel, rating, persons, studio, sourceTags);
            }
            else
            {
                tagged = bindPhotosToAlbum(albumDir, albumDir, rating, firstPerson, studio);
                // 扫描器是分批写 media_photo 的，刚绑完可能还有照片没入库，
                // 再补一轮统计与分级，避免相册显示 0 张 / 分级还是默认值
                Thread.sleep(6000);
                bindPhotosToAlbum(albumDir, albumDir, rating, firstPerson, studio);
            }
            if (recordId != null)
            {
                new JdbcTemplate(dataSource).update(
                        "update tool_web_download set state = ?, percent = 100, finished_at = now(), error = ? where id = ?",
                        tagged ? "done" : "error", tagged ? null : "扫描完成但没找到媒体行", recordId);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (RuntimeException e)
        {
            log.warn("回填分级/人物失败 job={} err={}", jobId, e.toString());
        }
    }

    /** 按 rel_path 找到刚入库的视频行，写分级与演员 */
    /**
     * 图片入册：把这次拷进去的照片**逻辑归属**到指定相册。
     *
     * <p>相册存在就把照片的 album_id 指过去；不存在先建一本（id 形如 aXXX，
     * 与媒体站扫描器建册的格式一致），再挂照片。这样管理端「相册」里立刻能看到。</p>
     */
    public boolean bindPhotosToAlbum(String albumTitle, String albumDir, String rating, String person, String studio)
    {
        return bindPhotosToAlbum(albumTitle, albumDir, rating, person, studio, true);
    }

    /**
     * 把照片归到相册，并补齐管理端需要的四样东西：
     * 人物关联（media_album_person）、张数与体积、封面（cover_path + thumbs/p/&lt;hash&gt;.jpg）、序号。
     */
    public boolean bindPhotosToAlbum(String albumTitle, String albumDir, String rating, String person, String studio,
            boolean fillStats)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String dir = LIB_FOLDER + "/" + albumDir;
        List<Map<String, Object>> found = jdbc.queryForList(
                "select id from media_album where dir = ? or title = ? limit 1", dir, albumTitle);
        String albumId;
        if (found.isEmpty())
        {
            albumId = nextAlbumId(jdbc);
            Long pid = personId(jdbc, person);
            jdbc.update("insert into media_album (id, title, dir, photo_count, bytes, content_rating, review_state, hidden, confirmed, sort, mtime, dir_key, identity_key, source) "
                    + "values (?, ?, ?, 0, 0, ?, 'pending', 0, 1, 0, now(), ?, '*', 'prefix')",
                    albumId, albumTitle, dir, normalizeRating(rating) == null ? "general" : normalizeRating(rating), dir);
            jdbc.update("insert into media_album_dir (album_id, root_id, dir) values (?, 1, ?)", albumId, dir);
            if (pid != null)
            {
                jdbc.update("update media_album set person_id = ? where id = ?", pid, albumId);
            }
        }
        else
        {
            albumId = String.valueOf(found.get(0).get("id"));
        }
        // 照片挂册：按目录前缀认（本次刚拷进去的那批）
        int bound = jdbc.update("update media_photo set album_id = ? where rel_path like ? and (album_id is null or album_id <> ?)",
                albumId, dir + "/%", albumId);
        if (!fillStats)
        {
            return true;
        }
        Long pid = person == null || person.isBlank() ? null : personId(jdbc, person);
        if (pid != null)
        {
            // 管理端「人物」列读的是关联表，只写 person_id 不显示
            List<Long> exist = jdbc.queryForList(
                    "select person_id from media_album_person where album_id = ? and person_id = ?", Long.class,
                    albumId, pid);
            if (exist.isEmpty())
            {
                jdbc.update("insert into media_album_person (album_id, person_id) values (?, ?)", albumId, pid);
            }
            jdbc.update("update media_album set person_id = ? where id = ?", pid, albumId);
        }
        // 序号按文件名顺序归一成 0 基：媒体站约定「封面是 seq=0」，
        // 管理端的封面缩略图 URL 就是 /thumb/p/<albumId>/<seq>，从 1 开始会取不到图
        jdbc.update("update media_photo p join (select id, row_number() over (order by rel_path) - 1 rn "
                + "from media_photo where album_id = ?) t on t.id = p.id set p.seq = t.rn "
                + "where p.album_id = ?", albumId, albumId);
        // 封面：取第一张照片，并把它的原图放进 thumbs/p/<hash>.jpg（媒体站按内容寻址取缩略图）
        List<Map<String, Object>> first = jdbc.queryForList(
                "select content_hash, rel_path, seq from media_photo where album_id = ? order by seq limit 1",
                albumId);
        if (!first.isEmpty())
        {
            String hash = first.get(0).get("content_hash") == null ? null
                    : String.valueOf(first.get(0).get("content_hash"));
            String rel = String.valueOf(first.get(0).get("rel_path"));
            String seq = String.valueOf(first.get(0).get("seq"));
            try
            {
                Path src = Paths.get(libRoot, rel);
                Path thumbRoot = thumbsDir("p");
                if (Files.exists(src))
                {
                    // 老命名（管理端封面走这个）：thumbs/p/<albumId>/<seq4>.jpg
                    Path thumbAlbumDir = thumbRoot.resolve(albumId);
                    Files.createDirectories(thumbAlbumDir);
                    String seqName = String.format("%04d.jpg", Integer.parseInt(seq));
                    Files.copy(src, thumbAlbumDir.resolve(seqName), StandardCopyOption.REPLACE_EXISTING);
                    jdbc.update("update media_album set cover_path = ? where id = ?",
                            "thumbs/p/" + albumId + "/" + seqName, albumId);
                    // 内容寻址（站端按 hash 取）
                    if (hash != null && !hash.isEmpty())
                    {
                        Files.copy(src, thumbRoot.resolve(hash + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            catch (IOException | NumberFormatException e)
            {
                log.warn("生成相册封面失败 album={} err={}", albumId, e.toString());
            }
        }
        // 刷新册的统计与分级/人物
        jdbc.update("update media_album a set a.photo_count = (select count(*) from media_photo p where p.album_id = a.id), "
                + "a.bytes = (select coalesce(sum(p.bytes),0) from media_photo p where p.album_id = a.id), "
                + "a.mtime = now() where a.id = ?", albumId);
        String safeRating = normalizeRating(rating);
        if (safeRating != null)
        {
            jdbc.update("update media_album set content_rating = ? where id = ?", safeRating, albumId);
        }
        if (studio != null && !studio.isBlank())
        {
            attachAlbumStudio(jdbc, albumId, studio);
        }
        log.info("图片入册 album={} dir={} 绑定照片 {} 张", albumId, dir, bound);
        return true;
    }

    /** 生成下一个相册 id（a185 → a186） */
    private String nextAlbumId(JdbcTemplate jdbc)
    {
        Long max = jdbc.query("select max(cast(substring(id, 2) as unsigned)) from media_album where id like 'a%'",
                rs -> rs.next() ? rs.getLong(1) : 1L);
        long next = (max == null ? 1L : max) + 1;
        return String.format("a%03d", next);
    }

    private void attachAlbumStudio(JdbcTemplate jdbc, String albumId, String studio)
    {
        Long sid = null;
        try
        {
            List<Long> rows = jdbc.queryForList("select id from media_studio where name = ? limit 1", Long.class, studio);
            sid = rows.isEmpty() ? null : rows.get(0);
        }
        catch (RuntimeException ignored)
        {
            sid = null;
        }
        if (sid != null)
        {
            jdbc.update("delete from media_album_studio where album_id = ?", albumId);
            jdbc.update("insert into media_album_studio (album_id, studio_id) values (?, ?)", albumId, sid);
        }
    }

    /** 图片入库：给相册设分级、绑人物（相册由扫描器按目录建出来） */
    private boolean applyAlbum(String albumDir, String rating, String person)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String dir = LIB_FOLDER + "/" + albumDir;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from media_album where dir = ? limit 1", dir);
        if (rows.isEmpty())
        {
            log.warn("扫描完成后没找到相册 dir={}", dir);
            return false;
        }
        String albumId = String.valueOf(rows.get(0).get("id"));
        String safeRating = normalizeRating(rating);
        if (safeRating != null)
        {
            jdbc.update("update media_album set content_rating = ? where id = ?", safeRating, albumId);
        }
        if (person != null && !person.isBlank())
        {
            Long pid = personId(jdbc, person);
            if (pid != null)
            {
                jdbc.update("update media_album set person_id = ? where id = ?", pid, albumId);
            }
        }
        return true;
    }

    /**
    /**
     * 复用源站标签：把「#每日大赛 #推特 …」这批词挂到媒体库视频上。
     * media_tag 是字典（有层级），media_video_tag 是关联（哪条视频用哪个标签、属哪个 facet）。
     * 字典里没有的就地新建，所以不会因为缺词丢标签。
     */
    private void applySourceTags(JdbcTemplate jdbc, String videoId, String sourceTags)
    {
        if (sourceTags == null || sourceTags.isBlank())
        {
            return;
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String raw : sourceTags.split(","))
        {
            String t = raw == null ? "" : raw.trim();
            if (t.isEmpty() || t.length() > 32)
            {
                continue;
            }
            names.add(t);
            if (names.size() >= 20)
            {
                break;
            }
        }
        if (names.isEmpty())
        {
            return;
        }
        try
        {
            jdbc.update("delete from media_video_tag where video_id = ? and facet = '标签'", videoId);
            for (String name : names)
            {
                Long tagId;
                List<Long> found = jdbc.queryForList("select id from media_tag where name = ? order by id limit 1",
                        Long.class, name);
                if (found.isEmpty())
                {
                    jdbc.update("insert into media_tag (name, use_count, create_time) values (?, 0, now())", name);
                    tagId = jdbc.queryForObject("select last_insert_id()", Long.class);
                }
                else
                {
                    tagId = found.get(0);
                }
                jdbc.update("insert into media_video_tag (video_id, facet, name, tag_id) values (?, '标签', ?, ?)",
                        videoId, name, tagId);
                jdbc.update("update media_tag set use_count = use_count + 1 where id = ?", tagId);
            }
            log.info("标签复用 id={} 共 {} 个", videoId, names.size());
        }
        catch (RuntimeException e)
        {
            log.warn("复用标签失败 id={} err={}", videoId, e.toString());
        }
    }

    /**
     * 用源站封面做媒体库封面。
     *
     * <p>用户端卡片显示的 <code>web/&lt;源站id&gt;/cover.jpg</code> 就是源站列表页那张图，
     * 这里把它同时放到两处：库目录里的 <code>&lt;标题&gt;-封面.jpg</code>（符合媒体站 poster_source=file 约定），
     * 以及内容寻址的 <code>thumbs/v/&lt;content_hash&gt;.jpg</code>（缩略图接口直接就能命中，不用等派生任务）。</p>
     */
    private void applySourceCover(JdbcTemplate jdbc, String videoId, String videoRel)
    {
        try
        {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select content_hash from media_video where id = ? limit 1", videoId);
            if (rows.isEmpty())
            {
                return;
            }
            String hash = rows.get(0).get("content_hash") == null ? null
                    : String.valueOf(rows.get(0).get("content_hash"));
            // 从库目录里的视频名反推：同名 -封面.jpg 若不存在，就用标题去 tool_web_post 找源站 id
            String baseName = videoRel.endsWith(".mp4") ? videoRel.substring(0, videoRel.length() - 4) : videoRel;
            String coverRel = LIB_FOLDER + "/" + baseName + "-封面.jpg";
            Path coverInLib = Paths.get(libRoot, coverRel);
            Path sourceCover = null;
            if (!Files.exists(coverInLib))
            {
                List<Map<String, Object>> posts = jdbc.queryForList(
                        "select source_id from tool_web_post where title = (select title from media_video where id = ?) limit 1",
                        videoId);
                if (!posts.isEmpty() && posts.get(0).get("source_id") != null)
                {
                    Path candidate = Paths.get(WEB_ROOT, String.valueOf(posts.get(0).get("source_id")), "cover.jpg");
                    if (Files.exists(candidate))
                    {
                        Files.createDirectories(coverInLib.getParent());
                        Files.copy(candidate, coverInLib, StandardCopyOption.REPLACE_EXISTING);
                        sourceCover = candidate;
                    }
                }
            }
            else
            {
                sourceCover = coverInLib;
            }
            if (sourceCover == null)
            {
                return;
            }
            jdbc.update("update media_video set poster_source = 'file', poster_file = ?, poster_path = ? where id = ?",
                    coverRel, hash == null ? null : "thumbs/v/" + hash + ".jpg", videoId);
            if (hash != null)
            {
                Path thumbDir = thumbsDir("v");
                Files.createDirectories(thumbDir);
                Files.copy(sourceCover, thumbDir.resolve(hash + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("封面已用源站图 id={} cover={}", videoId, coverRel);
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("写封面失败 id={} err={}", videoId, e.toString());
        }
    }

    private String normalizeRating(String rating)
    {
        if (rating == null || rating.isBlank())
        {
            return null;
        }
        return switch (rating.toLowerCase())
        {
            case "general", "g" -> "general";
            case "guidance", "pg" -> "guidance";
            case "restricted", "r", "x" -> "restricted";
            default -> null;
        };
    }

    private Long personId(JdbcTemplate jdbc, String person)
    {
        List<Long> p = jdbc.queryForList("select id from media_person where name = ? limit 1", Long.class, person);
        return p.isEmpty() ? null : p.get(0);
    }

    private boolean applyTags(String videoRel, String rating, List<String[]> persons, String studio,
            String sourceTags)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String rel = LIB_FOLDER + "/" + videoRel;
        // 扫描是分批写库的：刚结束时目标行可能还没出现，等一会儿再查（最多约 60 秒）
        List<Map<String, Object>> rows = java.util.List.of();
        for (int i = 0; i < 20 && rows.isEmpty(); i += 1)
        {
            rows = jdbc.queryForList("select id from media_video where rel_path = ? limit 1", rel);
            if (rows.isEmpty())
            {
                try
                {
                    Thread.sleep(3000);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (rows.isEmpty())
        {
            log.warn("扫描完成后没找到媒体行 rel={}", rel);
            return false;
        }
        String id = String.valueOf(rows.get(0).get("id"));
        String safeRating = normalizeRating(rating);
        if (safeRating != null)
        {
            jdbc.update("update media_video set content_rating = ? where id = ?", safeRating, id);
        }
        else if (rating != null && !rating.isBlank())
        {
            log.warn("忽略未知分级值 {}，保持扫描器默认", rating);
        }
        // 源站标签直接复用（媒体站没有的自动建字典）
        applySourceTags(jdbc, id, sourceTags);
        // 封面直接用源站那张（用户端卡片上的同一张图），不必等抽帧
        applySourceCover(jdbc, id, videoRel);
        // 多人物 + 角色：先清空再按本次设置写入（与管理端"整条覆盖"语义一致）
        if (persons != null && !persons.isEmpty())
        {
            jdbc.update("delete from media_video_actor where video_id = ?", id);
            for (String[] p : persons)
            {
                String name = p[0];
                String role = p.length > 1 && p[1] != null && !p[1].isBlank() ? p[1] : "actor";
                if (name == null || name.isBlank())
                {
                    continue;
                }
                jdbc.update("insert into media_video_actor (video_id, name, person_id, role) values (?, ?, ?, ?)",
                        id, name, personId(jdbc, name), role);
            }
        }
        if (studio != null && !studio.isBlank())
        {
            Long sid = null;
            try
            {
                List<Long> studioRows = jdbc.queryForList("select id from media_studio where name = ? limit 1",
                        Long.class, studio);
                sid = studioRows.isEmpty() ? null : studioRows.get(0);
            }
            catch (RuntimeException ignored)
            {
                sid = null;
            }
            if (sid != null)
            {
                jdbc.update("delete from media_video_studio where video_id = ?", id);
                jdbc.update("insert into media_video_studio (video_id, studio_id) values (?, ?)", id, sid);
            }
        }
        return true;
    }

    /** 目标已存在、与源同尺寸且不比源旧 → 说明上次已拷过，直接跳过：
     *  省一次整段复制（12 段约 800MB），也避开「文件被扫描/播放占用」导致的拷贝失败 */
    private boolean alreadyCopied(Path src, Path target)
    {
        try
        {
            return Files.exists(target)
                    && Files.size(src) == Files.size(target)
                    && Files.getLastModifiedTime(target).toMillis() >= Files.getLastModifiedTime(src).toMillis();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** 文件名清洗：去掉路径分隔与非法字符，限制长度 */
    private String safeName(String raw)
    {
        String s = raw.replaceAll("[\\\\/:*?\"<>|\r\n]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 80)
        {
            s = s.substring(0, 80).trim();
        }
        return s.isEmpty() ? "untitled" : s;
    }
}
