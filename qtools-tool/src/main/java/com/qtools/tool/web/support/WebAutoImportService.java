package com.qtools.tool.web.support;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 收藏即「保存到视频站」：收藏只是触发器，执行链复用 {@link com.qtools.tool.web.service.WebMediaImportService#importPost}，
 * 不再另起一套状态机（旧 WebArchiveService 已删，见 HANDOFF §24）。
 *
 * <p>队列就是 {@code tool_web_download} 表：mode='auto' 且 state='queued' 的行由后台线程串行消费。
 * 进度写同一张表的 percent，前端右下角「下载记录」面板直接可见。</p>
 *
 * <p><b>为什么是 mode='auto' 而不是 'media'</b>：'media' 是详情页「存入媒体站」弹窗自己的
 * 记录（{@code WebDownloadController#saveMedia} 同步导入，记录里带用户选的分级/人物/相册）。
 * 后台泵若也认 'media'，会把用户刚建、还没点确认的那条记录抢走，并用默认 params 覆盖他的选择。
 * 两者共用同一张表、同一套导入代码，只是队列范围分开。</p>
 *
 * @author qtools
 */
@Service
public class WebAutoImportService
{
    private static final Logger log = LoggerFactory.getLogger(WebAutoImportService.class);

    private static final String SIDECAR = "http://127.0.0.1:8765";

    /** 自动归档的默认元数据：人物素人（前端保存必填，后台没人填）、分级 restricted、不建相册 */
    private static final String DEFAULT_PARAMS =
            "{\"rating\":\"restricted\",\"persons\":[{\"name\":\"素人\",\"role\":\"\"}],\"album\":\"\",\"studio\":\"\"}";

    /** 等 sidecar 把段下载完：单段几十秒，12 段最坏十几分钟 */
    private static final long SEGMENT_TIMEOUT_MS = 30L * 60_000L;

    private static final long PUMP_INTERVAL_MS = 15_000L;

    /** 等媒体站扫描入库的上限：扫描一次几分钟，10 分钟够；超时就先写 partial */
    private static final long LIBRARY_TIMEOUT_MS = 10L * 60_000L;

    /** 扫描入库后等 awaitAndTag 回填封面/标签的上限（它的 3 秒等待 + 打标签，正常 10~30 秒） */
    private static final long TAG_TIMEOUT_MS = 3L * 60_000L;

    /** 一段 mp4 小于 1MB 视为还没下完（sidecar 的 downloadOne 用同一个阈值） */
    private static final long SEGMENT_MIN_BYTES = 1024L * 1024L;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.qtools.tool.web.service.WebMediaImportService mediaImportService;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile boolean pumping = false;

    private String home()
    {
        return System.getProperty("qtools.home", "E:/111/project/QTools");
    }

    /** 重启后把卡在 running 的作业退回 queued：进程被杀时任务不会自己结束，而导入是幂等的 */
    @EventListener(ApplicationReadyEvent.class)
    public void boot()
    {
        try
        {
            int n = jdbc.update("update tool_web_download set state='queued', error=null where mode='auto' and state='running'");
            if (n > 0)
            {
                log.info("收藏归档：{} 条中断的作业退回队列", Integer.valueOf(n));
            }
            // 其它模式（local / media）的进度只活在当时的线程里，进程一重启就是僵尸行：
            // 界面上永远「排队中 0%」「入库中 60%」，点删还删不掉旧状态。开机一次性标掉。
            int stale = jdbc.update("update tool_web_download set state='error', finished_at=now(),"
                    + " error='流程已中断（旧版本或后端重启前遗留），这条不会再推进，需要重新发起'"
                    + " where mode <> 'auto' and state in ('queued','preparing','importing','pending_import')");
            if (stale > 0)
            {
                log.info("收藏归档：{} 条遗留记录标记为中断", Integer.valueOf(stale));
            }
        }
        catch (RuntimeException e)
        {
            log.warn("收藏归档：重置中断作业失败 {}", e.toString());
        }
        Thread t = new Thread(this::pumpLoop, "media-import-pump");
        t.setDaemon(true);
        t.start();
    }

    private void pumpLoop()
    {
        while (true)
        {
            try
            {
                Thread.sleep(PUMP_INTERVAL_MS);
                pumpOnce();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Throwable e)
            {
                log.warn("收藏归档泵异常 {}", e.toString());
            }
        }
    }

    private void pumpOnce()
    {
        if (pumping)
        {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, post_id from tool_web_download where mode='auto' and state='queued' order by id limit 1");
        if (rows.isEmpty())
        {
            return;
        }
        pumping = true;
        try
        {
            runJob(((Number) rows.get(0).get("id")).longValue(), ((Number) rows.get(0).get("post_id")).longValue());
        }
        finally
        {
            pumping = false;
        }
    }

    /** 入队一条「保存到视频站」作业；已有运行中/已完成的作业就不重复排 */
    public Long enqueue(Long postId)
    {
        if (postId == null)
        {
            return null;
        }
        List<Map<String, Object>> busy = jdbc.queryForList(
                "select id from tool_web_download where post_id=? and mode='auto' and state in ('queued','running') order by id limit 1",
                postId);
        if (!busy.isEmpty())
        {
            return ((Number) busy.get(0).get("id")).longValue();
        }
        List<Map<String, Object>> done = jdbc.queryForList(
                "select id from tool_web_download where post_id=? and mode='auto' and state='done' order by id desc limit 1",
                postId);
        if (!done.isEmpty())
        {
            List<Map<String, Object>> work = jdbc.queryForList("select id from media_work where post_id=? limit 1", postId);
            if (!work.isEmpty())
            {
                return ((Number) done.get(0).get("id")).longValue();
            }
        }
        jdbc.update("insert into tool_web_download(post_id, mode, kind, state, percent, params, created_at)"
                + " values(?, 'auto', 'video', 'queued', 0, ?, now())", postId, DEFAULT_PARAMS);
        List<Map<String, Object>> id = jdbc.queryForList("select last_insert_id() as id");
        Long recordId = id.isEmpty() ? null : Long.valueOf(String.valueOf(id.get(0).get("id")));
        log.info("收藏归档入队 post={} record={}", postId, recordId);
        return recordId;
    }

    private void runJob(long recordId, long postId)
    {
        Map<String, Object> post;
        try
        {
            post = jdbc.queryForMap("select id, source_id, title, video_count, tags, content from tool_web_post where id=?", postId);
        }
        catch (RuntimeException e)
        {
            fail(recordId, "帖子不存在");
            return;
        }
        String sourceId = post.get("source_id") == null ? null : String.valueOf(post.get("source_id"));
        String title = post.get("title") == null ? ("post-" + postId) : String.valueOf(post.get("title"));
        int count = post.get("video_count") instanceof Number ? ((Number) post.get("video_count")).intValue() : 1;
        if (count < 1)
        {
            count = 1;
        }
        if (sourceId == null || sourceId.isBlank())
        {
            fail(recordId, "帖子缺少源站目录");
            return;
        }
        markState(recordId, "running", 1, null);
        try
        {
            // 1) 先把该帖所有段排进 sidecar 的下载队列（串行，每段几十秒）
            for (int i = 0; i < count; i += 1)
            {
                postDownload(postId, i);
            }
            int ready = waitSegments(sourceId, count, recordId);
            if (ready == 0)
            {
                fail(recordId, "段下载超时：没有可用的本地文件");
                return;
            }
            // 2) 复用导入流程：拷进媒体库、触发扫描、等扫描完再打标签写封面
            List<String[]> persons = new ArrayList<>();
            persons.add(new String[] { "素人", "" });
            Map<String, Object> res = mediaImportService.importPost(postId, Long.valueOf(recordId), sourceId, title,
                    "restricted", persons, "", "", "video",
                    post.get("tags") == null ? null : String.valueOf(post.get("tags")), "");
            if (!Boolean.TRUE.equals(res.get("ok")))
            {
                fail(recordId, String.valueOf(res.getOrDefault("message", "导入失败")));
                return;
            }
            markState(recordId, "running", 90, null);
            // 3) 等媒体站扫描把这几段写进 media_video。importPost 只负责"起扫描"，扫描是异步的
            //    （WebMediaImportService.awaitAndTag 就是为此轮询的），立刻查一定查不到 → 作品全是 partial。
            waitLibrary(title, count, recordId);
            // 4) 写作品行：一帖=一个作品，段序按文件名（video.mp4 / v2.mp4 …）
            List<Integer> imported = linkWork(postId, sourceId, title, count, post.get("content"));
            // 4) 只保留媒体库那一份（用户 2026-11-24 定）：镜像里的段 mp4 导入成功后删掉，
            //    只删已确认进了媒体库的那几段，没导入成功的留着等下轮重试。
            dropMirrorSegments(sourceId, imported);
            markState(recordId, "done", 100, null);
            log.info("收藏归档完成 post={} 段={} record={}", Long.valueOf(postId), Integer.valueOf(count), Long.valueOf(recordId));
        }
        catch (Throwable e)
        {
            log.warn("收藏归档失败 post={} err={}", Long.valueOf(postId), e.toString());
            fail(recordId, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void postDownload(long postId, int index)
    {
        try
        {
            String body = "{\"id\":" + postId + ",\"index\":" + index + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/download"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        }
        catch (Exception e)
        {
            log.warn("触发段下载失败 post={} index={} err={}", Long.valueOf(postId), Integer.valueOf(index), e.toString());
        }
    }

    /** 轮询 web 目录里各段是否存在；顺带把 5%~85% 的进度写进记录 */
    private int waitSegments(String sourceId, int count, long recordId) throws InterruptedException
    {
        Path dir = Paths.get(home(), "data", "media", "web", sourceId);
        long deadline = System.currentTimeMillis() + SEGMENT_TIMEOUT_MS;
        // 上一轮看到的字节数：两次采样一样才算下完。sidecar 现在先写 .part 再改名，
        // 但滚动发布期间旧进程仍可能直接写目标文件，不能只判"存在"。
        Map<Integer, Long> seen = new HashMap<>();
        int ready = 0;
        while (System.currentTimeMillis() < deadline)
        {
            ready = 0;
            for (int i = 0; i < count; i += 1)
            {
                Path seg = dir.resolve(segmentFile(i));
                if (!Files.exists(seg))
                {
                    continue;
                }
                long size;
                try
                {
                    size = Files.size(seg);
                }
                catch (java.io.IOException e)
                {
                    continue;
                }
                if (size >= SEGMENT_MIN_BYTES && Long.valueOf(size).equals(seen.get(Integer.valueOf(i))))
                {
                    ready += 1;
                }
                seen.put(Integer.valueOf(i), Long.valueOf(size));
            }
            if (ready >= count)
            {
                break;
            }
            int pct = count == 0 ? 5 : 5 + (int) Math.round(80.0 * ready / count);
            markState(recordId, "running", pct, null);
            Thread.sleep(5000L);
        }
        return ready;
    }

    /** index 0 → video.mp4，index N → v(N+1).mp4（与 sidecar / 导入流程的命名一致） */
    private static String segmentFile(int index)
    {
        return index == 0 ? "video.mp4" : ("v" + (index + 1) + ".mp4");
    }

    /**
     * 写作品行 + 段序。段在媒体库里的相对路径由导入流程决定：
     * {@code 每日大赛/<标题>.mp4}、{@code 每日大赛/<标题>-2.mp4} …
     *
     * <p>幂等：按 post_id 覆盖写，重跑不会产生第二个作品。</p>
     */
    private List<Integer> linkWork(long postId, String sourceId, String title, int count, Object content)
    {
        List<Integer> imported = new ArrayList<>();
        String base = "每日大赛/" + safeName(title);
        List<String> rels = new ArrayList<>();
        for (int i = 0; i < count; i += 1)
        {
            rels.add(i == 0 ? base + ".mp4" : base + "-" + (i + 1) + ".mp4");
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < rels.size(); i += 1)
        {
            in.append(i == 0 ? "?" : ",?");
        }
        List<Object> args = new ArrayList<>(rels);
        List<Map<String, Object>> vids = jdbc.queryForList(
                "select id, rel_path, ifnull(dur, 0) as dur from media_video where root_id=1 and rel_path in (" + in + ") order by rel_path",
                args.toArray());
        Map<String, Map<String, Object>> byRel = new LinkedHashMap<>();
        for (Map<String, Object> v : vids)
        {
            byRel.put(String.valueOf(v.get("rel_path")), v);
        }
        int segCount = 0;
        double totalDur = 0;
        for (String rel : rels)
        {
            Map<String, Object> v = byRel.get(rel);
            if (v != null)
            {
                segCount += 1;
                totalDur += ((Number) v.get("dur")).doubleValue();
            }
        }
        String cover = "/web/" + sourceId + "/cover.jpg";
        String blocks = content == null ? null : String.valueOf(content);
        jdbc.update("insert into media_work(post_id, source_id, title, synopsis_blocks, cover_path, seg_count, total_dur, state)"
                + " values(?,?,?,?,?,?,?,?) on duplicate key update source_id=values(source_id), title=values(title),"
                + " synopsis_blocks=values(synopsis_blocks), cover_path=values(cover_path), seg_count=values(seg_count),"
                + " total_dur=values(total_dur), state=values(state), updated_at=now()",
                postId, sourceId, title, blocks, cover, segCount, Double.valueOf(totalDur), segCount > 0 ? "ready" : "partial");
        Long workId = jdbc.queryForObject("select id from media_work where post_id=?", Long.class, postId);
        jdbc.update("delete from media_work_segment where work_id=?", workId);
        for (int i = 0; i < rels.size(); i += 1)
        {
            Map<String, Object> v = byRel.get(rels.get(i));
            if (v == null)
            {
                continue;
            }
            jdbc.update("insert into media_work_segment(work_id, seq, video_id) values(?,?,?)", workId, i, String.valueOf(v.get("id")));
            imported.add(Integer.valueOf(i));
        }
        log.info("作品写入 post={} work={} 段={}/{}", Long.valueOf(postId), workId, Integer.valueOf(segCount), Integer.valueOf(count));
        return imported;
    }

    /**
     * 等扫描把 {@code 每日大赛/<标题>[-N].mp4} 写进 media_video，最多等 10 分钟。
     *
     * <p>扫不完也往下走：linkWork 按实际命中的段写作品，命中 0 就是 partial，
     * 下一轮重跑（收藏再点一次）会补齐 —— 比整单失败更有用。</p>
     */
    private void waitLibrary(String title, int count, Long recordId) throws InterruptedException
    {
        String base = "每日大赛/" + safeName(title);
        String match = "v.root_id=1 and (v.rel_path in (?,?) or v.rel_path like ?)";
        Object[] args = { base + ".mp4", base + "-2.mp4", base + "-%" };
        long deadline = System.currentTimeMillis() + LIBRARY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            Integer hit = jdbc.queryForObject("select count(*) from media_video v where " + match, Integer.class, args);
            if (hit != null && hit.intValue() >= count)
            {
                break;
            }
            if (recordId != null)
            {
                markState(recordId.longValue(), "running", 92, null);
            }
            Thread.sleep(5000L);
        }
        // 扫描只写 media_video 行；封面与人物标签是 importPost 的 awaitAndTag 线程在扫描结束后才回填的。
        // done 必须等这一步：否则用户端已经显示「已保存到视频站」，管理端却还没封面/人物。
        // 等待信号就是那条下载记录本身 —— awaitAndTag 回填完会把它写成 done/error（见
        // WebMediaImportService.awaitAndTag 末尾），所以这里只看状态，不去猜标签 → 没人物的帖子（写真/ASMR）
        // 也能正常收尾，不会因为「必须有 人物 标签」白等三分钟。
        long tagDeadline = System.currentTimeMillis() + TAG_TIMEOUT_MS;
        while (System.currentTimeMillis() < tagDeadline)
        {
            String state = recordId == null ? null
                    : jdbc.queryForObject("select state from tool_web_download where id = ?", String.class, recordId);
            if ("done".equals(state) || "error".equals(state))
            {
                return;
            }
            if (recordId != null)
            {
                markState(recordId.longValue(), "running", 95, null);
            }
            Thread.sleep(3000L);
        }
        log.warn("等封面/人物超时 title={} 段={}", title, Integer.valueOf(count));
    }

    /** 删掉镜像目录里已经进了媒体库的段 mp4；任何一段删失败都不影响导入结果 */
    private void dropMirrorSegments(String sourceId, List<Integer> imported)
    {
        Path dir = Paths.get(home(), "data", "media", "web", sourceId);
        int dropped = 0;
        for (Integer idx : imported)
        {
            Path file = dir.resolve(segmentFile(idx.intValue()));
            try
            {
                if (Files.deleteIfExists(file))
                {
                    dropped += 1;
                }
            }
            catch (Exception e)
            {
                log.warn("删除镜像段失败 {} err={}", file, e.toString());
            }
        }
        if (dropped > 0)
        {
            log.info("镜像段已删除 source={} 数量={}", sourceId, Integer.valueOf(dropped));
        }
    }

    /** 详情页用：作业状态 + 作品（含可直接播放的本地流地址） */
    public Map<String, Object> describe(Long postId)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mediaState", "none");
        out.put("mediaPercent", 0);
        List<Map<String, Object>> rec = jdbc.queryForList(
                "select state, percent from tool_web_download where post_id=? and mode in ('auto','media') order by id desc limit 1", postId);
        if (!rec.isEmpty())
        {
            out.put("mediaState", rec.get(0).get("state") == null ? "none" : String.valueOf(rec.get(0).get("state")));
            out.put("mediaPercent", rec.get(0).get("percent") == null ? 0 : rec.get(0).get("percent"));
        }
        List<Map<String, Object>> works = jdbc.queryForList(
                "select id, title, cover_path, seg_count, total_dur, state from media_work where post_id=? limit 1", postId);
        if (works.isEmpty())
        {
            out.put("mediaWork", null);
            return out;
        }
        Map<String, Object> w = works.get(0);
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("id", w.get("id"));
        work.put("title", w.get("title"));
        work.put("coverPath", w.get("cover_path"));
        work.put("segCount", w.get("seg_count"));
        work.put("totalDur", w.get("total_dur"));
        work.put("state", w.get("state"));
        List<Map<String, Object>> segs = jdbc.queryForList(
                "select s.seq, v.rel_path, ifnull(v.root_id, 1) as root_id from media_work_segment s"
                        + " join media_video v on v.id = s.video_id where s.work_id=? order by s.seq", w.get("id"));
        List<String> streams = new ArrayList<>();
        for (Map<String, Object> s : segs)
        {
            String rel = String.valueOf(s.get("rel_path"));
            streams.add("/api/media/file?p=" + URLEncoder.encode(rel, StandardCharsets.UTF_8) + "&r=" + s.get("root_id"));
        }
        work.put("streams", streams);
        out.put("mediaWork", work);
        return out;
    }

    private void markState(long recordId, String state, int percent, String error)
    {
        try
        {
            jdbc.update("update tool_web_download set state=?, percent=?, error=? where id=?", state, Integer.valueOf(percent), error,
                    Long.valueOf(recordId));
        }
        catch (RuntimeException e)
        {
            log.warn("写作业状态失败 record={} err={}", Long.valueOf(recordId), e.toString());
        }
    }

    private void fail(long recordId, String reason)
    {
        markState(recordId, "failed", 0, reason == null ? "failed" : (reason.length() > 200 ? reason.substring(0, 200) : reason));
        log.warn("收藏归档失败 record={} reason={}", Long.valueOf(recordId), reason);
    }

    /** 与导入流程一致的文件名清洗（去路径分隔/非法字符，限长 80） */
    private static String safeName(String raw)
    {
        String s = raw == null ? "" : raw;
        s = s.replaceAll("[\\/:*?\"<>|\r\n]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > 80)
        {
            s = s.substring(0, 80).trim();
        }
        return s.isEmpty() ? "untitled" : s;
    }
}
