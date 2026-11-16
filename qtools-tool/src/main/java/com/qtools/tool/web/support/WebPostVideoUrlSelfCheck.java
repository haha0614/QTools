package com.qtools.tool.web.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检：修复历史遗留的坏播放地址。
 *
 * <p>背景：早期「重签回写」用 SQL 的 {@code json_extract} 从字符串里取第一段，
 * 只要传进去的是字符串字面量，取出来就是引号里的第一个字符 —— 于是库里出现了
 * {@code video_url = ","}（甚至整组 {@code video_urls = [",",",",…]}）。
 * 这类行播放时直接 manifestLoadError，而且用户完全无从判断原因。</p>
 *
 * <p>写入路径已经在 {@code updateVideoUrls} 里改成直接传值，这里只负责把存量数据拉回正轨：
 * <ul>
 *   <li>{@code video_url} 不是 http 开头、但数组里有合法地址 → 用数组第一条修好；</li>
 *   <li>数组本身也是坏的 → 打日志列出来（这种只能靠访问时触发重签）。</li>
 * </ul>
 */
@Component
public class WebPostVideoUrlSelfCheck
{
    private static final Logger log = LoggerFactory.getLogger(WebPostVideoUrlSelfCheck.class);

    /** 下载/重签 sidecar */
    private static final String SIDECAR = "http://127.0.0.1:8765";

    private final JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WebPostVideoUrlSelfCheck(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /** 周期自愈：启动时跑一次，之后每 5 分钟再查一遍（运行期间也可能被写脏） */
    @EventListener(ApplicationReadyEvent.class)
    public void repairBrokenVideoUrls()
    {
        startPeriodicHeal();
        repairOnce();
    }

    private void startPeriodicHeal()
    {
        Thread t = new Thread(() -> {
            while (true)
            {
                try
                {
                    Thread.sleep(5 * 60_000L);
                    repairOnce();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
                catch (Exception e)
                {
                    log.warn("周期自检异常：{}", e.toString());
                }
            }
        }, "video-url-heal");
        t.setDaemon(true);
        t.start();
    }

    private void repairOnce()
    {
        try
        {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select id, video_url, video_urls from tool_web_post "
                            + "where (video_url is null or video_url not like 'http%') "
                            + "and video_urls is not null and video_urls <> ''");
            if (rows.isEmpty())
            {
                return;
            }
            int fixed = 0;
            int needResign = 0;
            for (Map<String, Object> row : rows)
            {
                Long id = ((Number) row.get("id")).longValue();
                String raw = row.get("video_urls") == null ? "" : String.valueOf(row.get("video_urls"));
                String first = firstHttpUrl(raw);
                if (first == null)
                {
                    needResign += 1;
                    continue;
                }
                int count = countUrls(raw);
                jdbc.update("update tool_web_post set video_url = ?, video_count = ?, has_video = 1 where id = ?",
                        first, count, id);
                fixed += 1;
            }
            log.info("启动自检：修复播放地址 {} 条，另有 {} 条地址组本身已损坏（转为后台重签）", fixed, needResign);
            if (needResign > 0)
            {
                resignBrokenAsync();
            }
        }
        catch (Exception e)
        {
            // 自检失败不能挡住启动
            log.warn("启动自检（播放地址）失败：{}", e.toString());
        }
    }

    /**
     * 地址组本身坏掉（例如 [",",","]）的行：后台串行重签恢复。
     *
     * <p>这类数据只能靠真浏览器打开源站页面取地址，不能猜；放后台是为了不挡启动，
     * 串行是为了不和 sidecar 的下载队列抢浏览器。</p>
     */
    private void resignBrokenAsync()
    {
        Thread t = new Thread(() -> {
            List<Map<String, Object>> rows;
            try
            {
                rows = jdbc.queryForList("select id, url, video_urls from tool_web_post "
                        + "where video_urls is not null and video_urls <> ''");
            }
            catch (Exception e)
            {
                return;
            }
            int fixed = 0;
            int failed = 0;
            for (Map<String, Object> row : rows)
            {
                String raw = row.get("video_urls") == null ? "" : String.valueOf(row.get("video_urls"));
                if (raw.contains("http"))
                {
                    continue;
                }
                Long id = ((Number) row.get("id")).longValue();
                String pageUrl = row.get("url") == null ? null : String.valueOf(row.get("url"));
                if (pageUrl == null || pageUrl.isEmpty())
                {
                    failed += 1;
                    continue;
                }
                if (resignOne(id, pageUrl))
                {
                    fixed += 1;
                }
                else
                {
                    failed += 1;
                }
                try
                {
                    Thread.sleep(1500);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("启动自检（后台重签）：修复 {} 条，失败 {} 条", fixed, failed);
        }, "video-url-resign");
        t.setDaemon(true);
        t.start();
    }

    /** 对单条走一次 sidecar 重签并回写 */
    private boolean resignOne(Long id, String pageUrl)
    {
        try
        {
            String body = "{\"url\":\"" + pageUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/resign"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
            {
                return false;
            }
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(resp.body());
            List<String> urls = new ArrayList<>();
            if (json != null && json.getJSONArray("urls") != null)
            {
                for (Object o : json.getJSONArray("urls"))
                {
                    String s = String.valueOf(o);
                    if (s.startsWith("http"))
                    {
                        urls.add(s);
                    }
                }
            }
            if (urls.isEmpty())
            {
                return false;
            }
            jdbc.update("update tool_web_post set video_url = ?, video_urls = ?, video_count = ?, has_video = 1 "
                    + "where id = ?", urls.get(0), com.alibaba.fastjson2.JSON.toJSONString(urls), urls.size(), id);
            log.info("后台重签修复 id={} 共 {} 段", id, urls.size());
            return true;
        }
        catch (Exception e)
        {
            log.warn("后台重签失败 id={} err={}", id, e.toString());
            return false;
        }
    }

    private String firstHttpUrl(String raw)
    {
        try
        {
            List<?> list = com.alibaba.fastjson2.JSON.parseArray(raw);
            for (Object item : list)
            {
                String s = item == null ? "" : String.valueOf(item);
                if (s.startsWith("http"))
                {
                    return s;
                }
            }
        }
        catch (Exception ignored)
        {
            // JSON 坏了就当作没有可用地址
        }
        return null;
    }

    private int countUrls(String raw)
    {
        try
        {
            List<?> list = com.alibaba.fastjson2.JSON.parseArray(raw);
            return list.size() < 1 ? 1 : list.size();
        }
        catch (Exception ignored)
        {
            return 1;
        }
    }
}
