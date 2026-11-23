package com.qtools.tool.web.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 「收藏即整帖归档」的后台泵（2026-11-24）。
 *
 * <p>为什么要有它：收藏只插一行 {@code tool_web_user_item}，真正把视频全段＋图集＋封面＋正文落到本地
 * 是重活（每条要开浏览器抓详情、再逐段 ffmpeg 下载），放在 HTTP 请求线程里必然超时。
 * 这里用表列当队列：{@code archive_state='pending'} 就是待办，泵每 30 秒取 2 条丢给 sidecar，
 * sidecar 自己把状态推进到 running/done/partial/failed。</p>
 *
 * <p>为什么不用消息队列：本机单机、单用户，一条 SQL 就够；重启后 pending 还在，天然不丢任务。</p>
 */
@Component
public class WebArchiveService
{
    private static final Logger log = LoggerFactory.getLogger(WebArchiveService.class);

    /** 下载/重签 sidecar（与 WebPostVideoUrlSelfCheck 同一个常驻进程） */
    private static final String SIDECAR = "http://127.0.0.1:8765";

    /** 每个 tick 丢几条给 sidecar（sidecar 内部串行，多发只是排队） */
    private static final int PUMP_BATCH = 2;

    /** 源站变更检查的节流窗口：12 小时内同一条不重复检查（用户 2026-11-24 定） */
    private static final long CHECK_INTERVAL_MS = 12 * 3600_000L;

    private final JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** 正在检查源站的 id，避免并发点开同一条时重复发请求（DB 里的 source_checked_at 要等 sidecar 回来才写） */
    private final Set<Long> checking = ConcurrentHashMap.newKeySet();

    public WebArchiveService(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void boot()
    {
        // 上次进程在跑归档时被杀（重启/断电），running 会永远卡住：启动时统一退回待办。
        // sidecar 的归档接口是幂等的（已下载的段会覆盖重下），重跑没有副作用。
        try
        {
            int n = jdbc.update("update tool_web_post set archive_state='pending' where archive_state='running'");
            if (n > 0)
            {
                log.info("归档：{} 条上次没跑完，已退回待办", n);
            }
        }
        catch (Exception e)
        {
            log.warn("归档：重置 running 失败 {}", e.toString());
        }
        startPump();
    }

    private void startPump()
    {
        Thread t = new Thread(() -> {
            while (true)
            {
                try
                {
                    Thread.sleep(30_000L);
                    pumpOnce();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                catch (Exception e)
                {
                    log.warn("归档泵异常 {}", e.toString());
                }
            }
        }, "archive-pump");
        t.setDaemon(true);
        t.start();
    }

    private void pumpOnce()
    {
        List<Long> ids = jdbc.queryForList(
                "select id from tool_web_post where archive_state='pending' order by id limit " + PUMP_BATCH, Long.class);
        for (Long id : ids)
        {
            dispatch(id);
        }
    }

    /** 把一条丢给 sidecar；200 才把状态推进到 running（sidecar 不在就留在 pending 下轮再试） */
    private void dispatch(Long id)
    {
        HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/archive"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":" + id + "}"))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
            if (resp.statusCode() == 200)
            {
                jdbc.update("update tool_web_post set archive_state='running' where id=? and archive_state='pending'", id);
                log.info("归档：{} 已交给 sidecar", id);
            }
            else
            {
                log.warn("归档：{} sidecar 返回 {}，下轮重试", id, resp.statusCode());
            }
        }).exceptionally(e -> {
            log.warn("归档：{} 派发失败 {}", id, e.toString());
            return null;
        });
    }

    /** 收藏时调用：没归档过的排队；已 done / 正在跑的不要重排 */
    public void enqueueArchive(Long id)
    {
        try
        {
            int n = jdbc.update("update tool_web_post set archive_state='pending', archive_error=null "
                    + "where id=? and (archive_state is null or archive_state='none' or archive_state='failed')", id);
            if (n > 0)
            {
                log.info("归档：{} 已入队（收藏触发）", id);
            }
        }
        catch (Exception e)
        {
            log.warn("归档：{} 入队失败 {}", id, e.toString());
        }
    }

    /**
     * 本地已有的分段文件（给前端做本地优先播放）。
     *
     * @param count 段数（video_count / videos.size()）
     * @return 形如 {@code ["/web/202112/video.mp4","/web/202112/v2.mp4"]}；缺哪段就少哪项，前端只在
     *         「数量等于段数」时才用本地，避免半套本地半套代理导致跳段
     */
    public List<String> localFiles(String home, String sourceId, int count)
    {
        List<String> out = new ArrayList<>();
        if (home == null || sourceId == null || count <= 0)
        {
            return out;
        }
        for (int i = 0; i < count; i++)
        {
            String name = i == 0 ? "video.mp4" : "v" + (i + 1) + ".mp4";
            Path p = Path.of(home, "data", "media", "web", sourceId, name);
            if (Files.isRegularFile(p))
            {
                out.add("/web/" + sourceId + "/" + name);
            }
        }
        return out;
    }

    /**
     * 打开详情时的源站变更检查（12 小时节流，用户 2026-11-24 定）。
     *
     * <p>只对已归档的条目做：未归档时源站内容本来就是实时代理的，没什么可比的。
     * 检查在 sidecar 里跑（它才有正文容器的抽取逻辑），变了就把状态退回 pending 让泵重下；
     * 本次请求仍然按本地内容返回，用户不会被阻塞。</p>
     */
    public void maybeCheckSource(Long id)
    {
        try
        {
            List<Map<String, Object>> rs = jdbc.queryForList(
                    "select archive_state as s, source_checked_at as t from tool_web_post where id=?", id);
            if (rs.isEmpty())
            {
                return;
            }
            Map<String, Object> r = rs.get(0);
            if (!"done".equals(String.valueOf(r.get("s"))))
            {
                return;
            }
            Object checked = r.get("t");
            if (checked instanceof java.sql.Timestamp
                    && System.currentTimeMillis() - ((java.sql.Timestamp) checked).getTime() < CHECK_INTERVAL_MS)
            {
                return;
            }
            if (!checking.add(id))
            {
                return;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/check"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":" + id + "}"))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(resp -> {
                String body = resp.body() == null ? "" : resp.body();
                if (resp.statusCode() == 200 && body.contains("\"changed\":true"))
                {
                    jdbc.update("update tool_web_post set archive_state='pending' where id=? and archive_state='done'", id);
                    log.info("归档：{} 源站已改动，重新归档", id);
                }
            }).whenComplete((x, e) -> checking.remove(id));
        }
        catch (Exception e)
        {
            checking.remove(id);
            log.warn("归档：{} 变更检查失败 {}", id, e.toString());
        }
    }
}
