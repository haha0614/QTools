package com.qtools.tool.web.controller;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.tool.mapper.ToolWebPostMapper;

/**
 * 「内容聚合」的播放代理（P6.111）
 *
 * <p>为什么必须代理：源站播放列表是签名 HLS（{@code auth_key} 带时效），分片是 AES-128 加密且放在
 * 另一个域名上，浏览器直连会同时踩到跨域、防盗链、签名过期三件事。这里把播放列表整份改写，
 * 把分片和密钥都换成 {@code /api/tool/web/hls/{id}/part?u=…}，前端 hls.js 只跟本站打交道。</p>
 *
 * <p>只做转发，不落盘：源站内容归属不变，本站不再分发静态副本。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/tool/web")
public class WebPlayController
{
    private static final Logger log = LoggerFactory.getLogger(WebPlayController.class);

    /** 分片上限：单段通常 1–3 MB，超过这个说明取到了非分片内容（防止被当成任意文件下载通道） */
    private static final int PART_MAX_BYTES = 32 * 1024 * 1024;

    /** 允许代理的域名后缀（源站播放列表与分片所在的两家 CDN） */
    private static final String[] HOST_SUFFIXES = { "ohjzmm.cn", "doudou520.online", "hhjd.mobi" };

    /** 上次尝试自举 sidecar 的时间（30 秒节流，避免每次请求都 spawn） */
    private volatile long lastSpawnAttempt = 0L;

    /** 重签 sidecar（doc/watermark/resign-sidecar.mjs） */
    private static final String SIDECAR = System.getProperty("qtools.resign.url", "http://127.0.0.1:8765");

    /** 每个条目最近一次播放列表里出现过的域名，用于校验 part 的 u 参数 */
    private final Map<Long, PlaylistCache> playlistCache = new ConcurrentHashMap<>();
    /** 正在后台重签的帖子（避免同一帖子并发开多个浏览器） */
    private final java.util.Set<Long> resignInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    private ToolWebPostMapper toolWebPostMapper;

    /**
     * 触发一轮抓取：{@code POST /api/tool/web/refresh?quick=1}
     *
     * <p>quick=1 只抓最新一层（约 20 秒），前端在列表页打开/刷新时调用；quick=0 跑完整轮次（约 4 分钟）。
     * 立刻返回，抓取在 sidecar 里异步跑，前端过一会儿重新拉列表即可。</p>
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestParam(value = "quick", required = false, defaultValue = "1") int quick)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        ensureSidecar();
        try
        {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(SIDECAR + "/refresh?quick=" + (quick > 0 ? 1 : 0)))
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            out.put("started", resp.statusCode() == 202);
            out.put("quick", quick > 0);
        }
        catch (Exception e)
        {
            log.warn("触发抓取失败 quick={} err={}", quick, e.toString());
            out.put("started", false);
            out.put("error", "sidecar 不可用");
        }
        return out;
    }

    /**
     * 排队抓详情：{@code POST /api/tool/web/prefetch}，body {@code {"ids":[1,2,3],"priority":"view"}}
     *
     * <p>前端在列表加载完把"当前页里还没有详情的 id"丢进来，sidecar 的队列串行抓（并发 1），
     * 抓完前端重载当前页即可看到封面与正文。priority=open 表示用户已经点开，插队头。</p>
     */
    @PostMapping("/prefetch")
    public Map<String, Object> prefetch(@RequestBody(required = false) Map<String, Object> body)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        Object rawIds = body == null ? null : body.get("ids");
        List<Object> ids = rawIds instanceof List ? (List<Object>) rawIds : List.of();
        String priority = body != null && "open".equals(body.get("priority")) ? "open" : "view";
        if (ids.isEmpty())
        {
            out.put("queued", 0);
            return out;
        }
        ensureSidecar();
        try
        {
            String json = "{\"ids\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ids)
                    + ",\"priority\":\"" + priority + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/prefetch"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            out.put("queued", resp.statusCode() == 200 ? ids.size() : 0);
            out.put("sidecar", resp.statusCode());
        }
        catch (Exception e)
        {
            log.warn("预取入队失败 ids={} err={}", ids.size(), e.toString());
            out.put("queued", 0);
            out.put("error", "sidecar 不可用");
        }
        return out;
    }

    /**
     * 视频入库：{@code POST /api/tool/web/download?postId=1}
     *
     * <p>交给 sidecar 的串行队列：ffmpeg 直接拉 HLS 再 -c copy 存 mp4（不转码），
     * 落盘到 {@code data/media/web/<源站id>/video.mp4}，之后详情页直接播本地文件、不再受签名过期影响。</p>
     */
    @PostMapping("/download")
    public Map<String, Object> download(@RequestParam("postId") Long postId,
            @RequestParam(value = "index", required = false, defaultValue = "0") int index)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        ensureSidecar();
        try
        {
            // index>0 表示多段视频的第 N 段：sidecar 会存成 v2.mp4 / v3.mp4 …
            String body = "{\"id\":" + postId + ",\"index\":" + index + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/download"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            out.put("ok", resp.statusCode() == 200);
        }
        catch (Exception e)
        {
            log.warn("触发下载失败 postId={} err={}", postId, e.toString());
            out.put("ok", false);
            out.put("error", "sidecar 不可用");
        }
        return out;
    }

    /** 播放入口：{@code GET /api/tool/web/play/{id}} → 前端拿 src 交给 hls.js */
    @GetMapping("/play/{id}")
    public Map<String, Object> play(@PathVariable("id") Long id)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        String videoUrl = toolWebPostMapper.selectVideoUrl(id);
        if (videoUrl == null || videoUrl.isEmpty())
        {
            out.put("error", "这条没有可播放的视频");
            return out;
        }
        out.put("src", "/api/tool/web/hls/" + id + "/playlist.m3u8");
        out.put("kind", "hls");
        return out;
    }

    /** 播放列表：取源站 m3u8 后把分片/密钥地址全部改写成代理地址 */
    @GetMapping("/hls/{id}/playlist.m3u8")
    public ResponseEntity<byte[]> playlist(@PathVariable("id") Long id,
            @RequestParam(value = "i", required = false, defaultValue = "0") int index)
    {
        String videoUrl = toolWebPostMapper.selectVideoUrl(id, index);
        // 历史数据里出现过被写坏的地址（例如只剩 "]}")：这种直接重签，别丢给 HTTP 客户端抛异常
        if (videoUrl != null && (videoUrl.length() < 20 || !videoUrl.startsWith("http")))
        {
            log.info("播放地址异常 id={} index={} url={}", id, index, videoUrl);
            String fixed = resign(id, index);
            if (fixed != null)
            {
                videoUrl = fixed;
            }
        }
        if (videoUrl == null || videoUrl.isEmpty() || !videoUrl.startsWith("http"))
        {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new byte[0]);
        }
        try
        {
            HttpResponse<byte[]> resp = http.send(request(videoUrl), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200)
            {
                // 签名多半过期了（源站 CDN 对 auth_key 有时间窗）。重签要真浏览器，
                // 由本地 sidecar 代劳，拿到新地址后回写并重试一次。
                log.info("播放列表失效 id={} status={}，后台重签并快速返回", id, resp.statusCode());
                // 重签要开真浏览器，慢且可能排队；播放器等不起。
                // 这里立刻返回 502（前端提示"源站地址过期，正在重新签名"），
                // 同时后台异步重签，用户等几秒再点播放就好了。
                resignAsync(id, index);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new byte[0]);
            }
            String text = new String(resp.body(), StandardCharsets.UTF_8);
            Set<String> hosts = new LinkedHashSet<>();
            String rewritten = rewrite(text, id, hosts);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            // 一条帖子可能有多段视频，各自的 CDN 域名不同。
            // 这里必须「合并」而不是覆盖：覆盖会让另一段的 key/分片域名从白名单里消失，
            // 表现为 keyLoadError（实测第 2 段播放失败就是这个原因）。
            playlistCache.compute(id, (k, old) -> old == null ? new PlaylistCache(hosts) : old.merge(hosts));
            return new ResponseEntity<>(rewritten.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
        }
        catch (Exception e)
        {
            log.warn("播放列表异常 id={} err={}", id, e.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new byte[0]);
        }
    }

    /** 分片 / 密钥转发：{@code GET /api/tool/web/hls/{id}/part?u=<源地址>} */
    @GetMapping("/hls/{id}/part")
    public ResponseEntity<byte[]> part(@PathVariable("id") Long id, @RequestParam("u") String u)
    {
        String target = u == null ? "" : u.trim();
        if (!isAllowed(id, target))
        {
            log.warn("拒绝代理未知地址 id={} u={}", id, abbreviate(target));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new byte[0]);
        }
        try
        {
            // 源站 CDN 偶发 5xx/超时：重试一次再放弃（hls.js 收到 5xx 会整段丢弃）
            HttpResponse<byte[]> resp = http.send(request(target), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200)
            {
                resp = http.send(request(target), HttpResponse.BodyHandlers.ofByteArray());
            }
            if (resp.statusCode() != 200)
            {
                log.warn("分片源站返回 {} id={} u={}", resp.statusCode(), id, abbreviate(target));
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0)
            {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new byte[0]);
            }
            if (body.length > PART_MAX_BYTES)
            {
                log.warn("分片过大 id={} size={}", id, body.length);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new byte[0]);
            }
            HttpHeaders headers = new HttpHeaders();
            String upstreamType = resp.headers().firstValue("content-type").orElse("");
            headers.setContentType(MediaType.parseMediaType(
                    upstreamType.isEmpty() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : upstreamType));
            headers.set(HttpHeaders.CACHE_CONTROL, target.contains("crypt.key") ? "no-store" : "public, max-age=1800");
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        }
        catch (Exception e)
        {
            log.warn("分片转发异常 id={} err={}", id, e.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new byte[0]);
        }
    }

    /**
     * 调本机重签 sidecar 拿一份新签名地址（sidecar 用真浏览器打开源站详情页，
     * 普通 HTTP 请求拿不到那段配置 JSON）。sidecar 没起时返回 null，播放降级为 502。
     */
    private String resign(Long id)
    {
        return resign(id, 0);
    }

    /** 后台重签：不阻塞播放请求；同一帖子同一时刻只跑一个 */
    private void resignAsync(Long id, int index)
    {
        if (!resignInFlight.add(id))
        {
            return;
        }
        Thread t = new Thread(() -> {
            try
            {
                String fresh = resign(id, index);
                log.info("后台重签完成 id={} ok={}", id, fresh != null);
            }
            catch (Exception e)
            {
                log.warn("后台重签失败 id={} err={}", id, e.toString());
            }
            finally
            {
                resignInFlight.remove(id);
            }
        }, "resign-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** 重签，并返回第 index 段视频的地址（多视频必须按段取，否则第 2 段会播成第 1 段） */
    private String resign(Long id, int index)
    {
        ensureSidecar();
        String sourceUrl = toolWebPostMapper.selectSourceUrl(id);
        if (sourceUrl == null || sourceUrl.isEmpty())
        {
            return null;
        }
        try
        {
            String body = "{\"url\":\"" + sourceUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            // 重签是真浏览器去开源站页面，忙的时候（sidecar 正在跑自动刷新）可能很久；
            // 但播放器等不起：给 12 秒上限，超时就返回失败让前端提示，而不是让请求挂着
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/resign"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
            {
                log.warn("重签 sidecar 返回 {} id={}", resp.statusCode(), id);
                return null;
            }
            String json = resp.body();
            // sidecar 现在回 { url, urls:[…] }：多视频必须按段取，
            // 只取 url[0] 会让"第 2 段过期"的时候错播成第 1 段（实测过）
            java.util.List<String> list = new java.util.ArrayList<>();
            int arr = json.indexOf("\"urls\":[");
            if (arr >= 0)
            {
                int p = arr + 9;
                while (p < json.length())
                {
                    int q1 = json.indexOf('"', p);
                    if (q1 < 0)
                    {
                        break;
                    }
                    StringBuilder sb = new StringBuilder();
                    int i = q1 + 1;
                    for (; i < json.length(); i++)
                    {
                        char c = json.charAt(i);
                        if (c == '"')
                        {
                            break;
                        }
                        if (c == '\\' && i + 1 < json.length())
                        {
                            i++;
                            sb.append(json.charAt(i));
                        }
                        else
                        {
                            sb.append(c);
                        }
                    }
                    if (sb.length() > 0)
                    {
                        list.add(sb.toString());
                    }
                    if (i + 1 < json.length() && json.charAt(i + 1) == ']')
                    {
                        break;
                    }
                    p = i + 2;
                }
            }
            if (list.isEmpty())
            {
                int k = json.indexOf("\"url\":\"");
                if (k < 0)
                {
                    return null;
                }
                int start = k + 7;
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < json.length(); i++)
                {
                    char c = json.charAt(i);
                    if (c == '"')
                    {
                        break;
                    }
                    if (c == '\\' && i + 1 < json.length())
                    {
                        i++;
                        sb.append(json.charAt(i));
                    }
                    else
                    {
                        sb.append(c);
                    }
                }
                if (sb.length() > 0)
                {
                    list.add(sb.toString());
                }
            }
            if (list.isEmpty())
            {
                return null;
            }
            // 整组回写，下次播放就不用再重签
            toolWebPostMapper.updateVideoUrls(id, list.get(0), com.alibaba.fastjson2.JSON.toJSONString(list),
                    list.size());
            String url = index < list.size() ? list.get(index) : list.get(0);
            log.info("重签成功 id={} 共{}段 取第{}段", id, list.size(), index);
            return url;
        }
        catch (Exception e)
        {
            log.warn("重签 sidecar 调用失败 id={} err={}", id, e.toString());
            return null;
        }
    }

    /**
     * sidecar 没起就自己拉起来（只尝试一次/JVM）。启动脚本是 GBK 的 bat，不适合用文本工具改，
     * 让后端自带这层自举更省事：node 和脚本都在 qtools.home 下，路径可推导。
     */
    private void ensureSidecar()
    {
        // 先探活：sidecar 可能被外部重启/杀掉，用一次性标志会误判"已启动过就不再管"
        if (probeSidecar())
        {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSpawnAttempt < 30000L)
        {
            return;
        }
        lastSpawnAttempt = now;
        try
        {
            String home = System.getProperty("qtools.home", "E:/111/project/QTools");
            File node = new File(home, "tools/node/node.exe");
            File script = new File(home, "QTools/doc/watermark/resign-sidecar.mjs");
            if (!node.isFile() || !script.isFile())
            {
                log.warn("重签 sidecar 无法自举：node={} script={}", node, script);
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), script.getAbsolutePath());
            pb.directory(script.getParentFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(home, "data/logs/resign-sidecar.log")));
            pb.start();
            for (int i = 0; i < 10 && !probeSidecar(); i++)
            {
                Thread.sleep(600);
            }
            log.info("重签 sidecar 启动完成 reachable={}", probeSidecar());
        }
        catch (Exception e)
        {
            log.warn("重签 sidecar 自举失败 {}", e.toString());
        }
    }

    /** 探活：sidecar 只认 POST /resign，GET 会 404，但能连上就说明进程在 */
    private boolean probeSidecar()
    {
        try
        {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/ping"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** 把播放列表里的绝对地址换成代理地址；同时记下出现过的域名 */
    private String rewrite(String playlist, Long id, Set<String> hosts)
    {
        StringBuilder out = new StringBuilder(playlist.length() + 512);
        for (String line : playlist.split("\r?\n"))
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            if (trimmed.startsWith("#"))
            {
                // 只处理 #EXT-X-KEY 里的 URI="…"（AES 密钥也走代理，否则跨域拿不到）
                int at = trimmed.indexOf("URI=\"");
                if (at >= 0)
                {
                    int end = trimmed.indexOf('"', at + 5);
                    if (end > at)
                    {
                        String keyUrl = trimmed.substring(at + 5, end);
                        record(hosts, keyUrl);
                        trimmed = trimmed.substring(0, at + 5) + proxyUrl(id, keyUrl) + trimmed.substring(end);
                    }
                }
                out.append(trimmed).append('\n');
                continue;
            }
            record(hosts, trimmed);
            out.append(proxyUrl(id, trimmed)).append('\n');
        }
        return out.toString();
    }

    private static String proxyUrl(Long id, String absoluteUrl)
    {
        return "/api/tool/web/hls/" + id + "/part?u="
                + URLEncoder.encode(absoluteUrl, StandardCharsets.UTF_8);
    }

    private static void record(Set<String> hosts, String url)
    {
        try
        {
            String host = URI.create(url).getHost();
            if (host != null)
            {
                hosts.add(host.toLowerCase(Locale.ROOT));
            }
        }
        catch (Exception ignored)
        {
            // 坏地址不记录，part 环节会自然拒绝
        }
    }

    /** u 参数校验：只放行 http(s) + 已知域名（含本次播放列表里出现过的域名），并挡掉内网地址 */
    private boolean isAllowed(Long id, String url)
    {
        URI uri;
        try
        {
            uri = URI.create(url);
        }
        catch (Exception e)
        {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme))
        {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty())
        {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.startsWith("127.") || lower.startsWith("10.")
                || lower.startsWith("192.168.") || lower.startsWith("169.254.") || lower.startsWith("[") )
        {
            return false;
        }
        if (lower.startsWith("172."))
        {
            String[] seg = lower.split("\\.");
            if (seg.length > 1)
            {
                try
                {
                    int second = Integer.parseInt(seg[1]);
                    if (second >= 16 && second <= 31)
                    {
                        return false;
                    }
                }
                catch (NumberFormatException ignored)
                {
                    // 不是数字就不是内网段
                }
            }
        }
        PlaylistCache cached = playlistCache.get(id);
        if (cached != null && cached.hosts.contains(lower))
        {
            return true;
        }
        for (String suffix : HOST_SUFFIXES)
        {
            if (lower.equals(suffix) || lower.endsWith("." + suffix))
            {
                return true;
            }
        }
        return false;
    }

    private static HttpRequest request(String url)
    {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36")
                .header("Referer", "https://www.mrds66.com/")
                .GET()
                .build();
    }

    private static String abbreviate(String text)
    {
        if (text == null)
        {
            return "";
        }
        return text.length() <= 90 ? text : text.substring(0, 90) + "…";
    }

    /** 播放列表里出现过的域名（10 分钟内有效，够一次播放用） */
    private static final class PlaylistCache
    {
        private final Set<String> hosts;

        private PlaylistCache(Set<String> hosts)
        {
            this.hosts = new LinkedHashSet<>(hosts);
        }

        /** 把另一段视频的域名并进来（同一 id 的所有段共享一份白名单） */
        private PlaylistCache merge(Set<String> more)
        {
            this.hosts.addAll(more);
            return this;
        }
    }
}
