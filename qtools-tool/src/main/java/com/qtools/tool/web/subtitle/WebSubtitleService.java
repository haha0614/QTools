package com.qtools.tool.web.subtitle;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.qtools.media.support.MediaTranscribeMaterial;
import com.qtools.media.support.SubtitleParser;
import com.qtools.tool.mapper.ToolWebPostMapper;
import com.qtools.tool.mapper.ToolWebSubtitleMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 视频双语字幕：抽音轨 → whisper → 批量翻译 → 写 VTT（docs/视频字幕-实施交接.md §T5）
 *
 * <p>单飞：同一 {@code (postId, seg)} 只会有一个任务在跑（{@link #running} 里的 Future）。
 * 线程池默认 1 线程（{@code qtools.media.subtitle.workers}），whisper 本身已经吃满 CPU，并行只会互相拖慢。</p>
 *
 * <p>所有失败都落到 {@code tool_web_subtitle.err}（调用方仍是 HTTP 200），不往上抛。</p>
 *
 * @author qtools
 */
@Service
public class WebSubtitleService
{
    private static final Logger log = LoggerFactory.getLogger(WebSubtitleService.class);

    /** 排队中 */
    private static final int P_QUEUED = 5;

    /** 抽完音轨 */
    private static final int P_AUDIO = 10;

    /** 分片转写进度区间：每转完一片推进一段，前端能看到真实进度 */
    private static final int P_TRANSCRIBE_START = 15;

    private static final int P_TRANSCRIBE_END = 60;

    /** 翻译完 */
    private static final int P_TRANSLATED = 85;

    /** 写文件完 */
    private static final int P_WRITTEN = 92;

    /**
     * 单片转写时长：整片一次喂 whisper 会退化成除噪标记（实测 80 分钟窗口后半段只剩「(息を吹き出す)」），
     * 120 秒窗口在同一段能正常出日语台词，所以按小片切开、各片独立转写
     */
    private static final long CHUNK_MS = 2 * 60000L;

    /** err 列宽 */
    private static final int ERR_MAX = 500;

    private static final String SIDECAR = System.getProperty("qtools.resign.url", "http://127.0.0.1:8765");

    private final ToolWebSubtitleMapper subtitleMapper;

    private final ToolWebPostMapper postMapper;

    private final SubtitleProperties properties;

    private final MediaTranscribeMaterial transcribeMaterial;

    private final SubtitleTranslateService translateService;

    private final HttpClient http;

    private final ExecutorService pool;

    /** 单飞表：key = postId:seg */
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

    @Value("${server.port:8080}")
    private int port = 8080;

    public WebSubtitleService(ToolWebSubtitleMapper subtitleMapper, ToolWebPostMapper postMapper,
            SubtitleProperties properties, MediaTranscribeMaterial transcribeMaterial,
            SubtitleTranslateService translateService)
    {
        this.subtitleMapper = subtitleMapper;
        this.postMapper = postMapper;
        this.properties = properties;
        this.transcribeMaterial = transcribeMaterial;
        this.translateService = translateService;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.pool = Executors.newFixedThreadPool(properties.getWorkers(), runnable -> {
            Thread thread = new Thread(runnable, "subtitle-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ 对外

    /** 当前状态（status=1 但超 2 小时 → 按 0 返回，前端才知道可以重建） */
    public Map<String, Object> get(Long postId, int seg)
    {
        int useSeg = Math.max(0, seg);
        // 纯读：没有行就按 status=0 合成返回，不落库。
        // 前端每开一帖都会 GET 一次，用 insertIfAbsent 会让「只看过没生成过」的帖子无限长行。
        return data(postId, useSeg, subtitleMapper.selectOne(postId, useSeg));
    }

    /** 触发构建；已成功且 force=false 时直接返回现状 */
    public Map<String, Object> build(Long postId, int seg, boolean force)
    {
        int useSeg = Math.max(0, seg);
        Map<String, Object> row = row(postId, useSeg);
        if (!properties.isEnabled())
        {
            subtitleMapper.markFailed(postId, useSeg, truncate("字幕功能未开启（qtools.media.subtitle.enabled=false）"));
            return data(postId, useSeg, row(postId, useSeg));
        }
        int status = intOf(row.get("status"));
        boolean stale = stale(row);
        if (status == 2 && !force)
        {
            return data(postId, useSeg, row);
        }
        String key = key(postId, useSeg);
        Future<?> current = running.get(key);
        boolean alive = current != null && !current.isDone();
        if (alive && status == 1 && !stale)
        {
            // 已经在跑：不重复排队
            return data(postId, useSeg, row);
        }
        subtitleMapper.markRunning(postId, useSeg, P_QUEUED, engine());
        try
        {
            running.put(key, pool.submit(() -> run(postId, useSeg)));
        }
        catch (RuntimeException e)
        {
            log.warn("字幕任务排队失败 postId={} seg={} {}", postId, useSeg, e.getClass().getSimpleName());
            subtitleMapper.markFailed(postId, useSeg, "字幕任务排队失败");
        }
        log.info("字幕任务已排队 postId={} seg={} force={}", postId, useSeg, force);
        return data(postId, useSeg, row(postId, useSeg));
    }

    /** 成品 VTT 文本；还没生成好返回 null（控制器据此给 404） */
    public String vtt(Long postId, int seg)
    {
        Path file = finalPath(postId, Math.max(0, seg));
        if (!Files.isRegularFile(file))
        {
            return null;
        }
        try
        {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            log.warn("读字幕文件失败 postId={} seg={} {}", postId, seg, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 删掉一条帖子的字幕（全部段的行 + 文件） */
    public Map<String, Object> remove(Long postId, int seg)
    {
        int useSeg = Math.max(0, seg);
        Future<?> current = running.remove(key(postId, useSeg));
        if (current != null)
        {
            current.cancel(true);
        }
        subtitleMapper.deleteByPost(postId);
        Path dir = Path.of(properties.getEffectiveDir(), String.valueOf(postId));
        deleteTree(dir);
        log.info("字幕已删除 postId={} seg={}", postId, useSeg);
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("status", 0);
        empty.put("progress", 0);
        empty.put("cueCount", 0);
        empty.put("durMs", 0L);
        empty.put("engine", "");
        empty.put("err", "");
        empty.put("src", "");
        empty.put("updatedAt", 0L);
        return empty;
    }

    // ------------------------------------------------------------------ 主流程

    private void run(Long postId, int seg)
    {
        long started = System.currentTimeMillis();
        Path work = null;
        try
        {
            Path dir = Path.of(properties.getEffectiveDir(), String.valueOf(postId));
            Files.createDirectories(dir);
            work = Files.createTempDirectory(dir, "tmp-" + seg + "-");
            Path wav = work.resolve("audio.wav");
            String playlist = "http://127.0.0.1:" + port + "/api/tool/web/hls/" + postId + "/playlist.m3u8?asr=1&i=" + seg;
            // 抽取走的是逐段代理（源站 CDN 快时 80 分钟片 5 分钟，慢时 25 分钟以上），
            // 上限取 40 分钟：15 分钟会在大片 + 慢 CDN 上假超时（2026-11-26 21:05 实测：
            // 3.2 倍实时，60 秒音频 18.9 秒，全片约 25 分钟）。
            long extractTimeout = Math.min(properties.getEffectiveTimeoutMs(), 40 * 60000L);
            String reason = transcribeMaterial.extractAudio(playlist, wav, extractTimeout);
            if (!reason.isEmpty())
            {
                // 源站地址过期是最常见原因（HLS 签名有时效）：重签一次再试
                log.info("抽音频失败，重签后重试 postId={} seg={} reason={}", postId, seg, reason);
                resign(postId, seg);
                reason = transcribeMaterial.extractAudio(playlist, wav, extractTimeout);
            }
            if (!reason.isEmpty())
            {
                fail(postId, seg, "音频抽取失败：" + reason);
                return;
            }
            subtitleMapper.updateProgress(postId, seg, P_AUDIO);

            List<String> notes = new ArrayList<>(2);
            List<SubtitleParser.Cue> cues = transcribeChunks(wav, postId, seg, notes);
            if (cues.isEmpty())
            {
                fail(postId, seg, notes.isEmpty() ? "没有识别出任何字幕内容" : String.join("；", notes));
                return;
            }

            List<String> ja = new ArrayList<>(cues.size());
            long durMs = 0;
            for (SubtitleParser.Cue cue : cues)
            {
                ja.add(cue.text);
                durMs = Math.max(durMs, cue.endMs);
            }
            SubtitleTranslateService.Result translated = translateService.translate(ja);
            subtitleMapper.updateProgress(postId, seg, P_TRANSLATED);

            List<SubtitleVtt.Line> lines = new ArrayList<>(cues.size());
            for (int i = 0; i < cues.size(); i++)
            {
                SubtitleParser.Cue cue = cues.get(i);
                String zh = i < translated.zh.size() ? translated.zh.get(i) : "";
                lines.add(new SubtitleVtt.Line(cue.startMs, cue.endMs, cue.text, zh));
            }
            String text = SubtitleVtt.build(lines);
            int cueCount = SubtitleVtt.normalize(lines).size();
            Path target = finalPath(postId, seg);
            write(target, text, work);
            subtitleMapper.updateProgress(postId, seg, P_WRITTEN);
            subtitleMapper.markDone(postId, seg, durMs, cueCount, target.toString(), engine());
            if (!translated.err.isEmpty())
            {
                notes.add(translated.err);
            }
            if (!notes.isEmpty())
            {
                subtitleMapper.markErrNote(postId, seg, truncate(String.join("；", notes)));
            }
            log.info("字幕生成完成 postId={} seg={} cues={} durMs={} ms={}", postId, seg, cueCount, durMs,
                    System.currentTimeMillis() - started);
        }
        catch (Throwable e)
        {
            log.warn("字幕生成异常 postId={} seg={} {}", postId, seg, e.getClass().getSimpleName());
            fail(postId, seg, "字幕生成失败（" + e.getClass().getSimpleName() + "）");
        }
        finally
        {
            deleteTree(work);
            running.remove(key(postId, seg));
        }
    }

    /**
     * 分片转写：一片一片喂 whisper，cue 时间轴按片起点平移；单片失败只记 note，不整集失败
     *
     * @return 全片 cue（按时间升序）
     */
    private List<SubtitleParser.Cue> transcribeChunks(Path wav, Long postId, int seg, List<String> notes)
            throws IOException
    {
        long totalMs = wavDurationMs(wav);
        int chunks = (int)Math.max(1L, (totalMs + CHUNK_MS - 1) / CHUNK_MS);
        List<SubtitleParser.Cue> all = new ArrayList<>(1024);
        for (int i = 0; i < chunks; i++)
        {
            long from = (long)i * CHUNK_MS;
            long dur = totalMs > 0 ? Math.min(CHUNK_MS, totalMs - from) : CHUNK_MS;
            Path part = wav.getParent().resolve("chunk-" + i + ".wav");
            String reason = transcribeMaterial.sliceWav(wav, part, from, dur, properties.getEffectiveTimeoutMs());
            if (reason.isEmpty())
            {
                MediaTranscribeMaterial.Transcript piece = transcribeMaterial.transcribeWav(part,
                        properties.getWhisperModel(), properties.getWhisperLang(), properties.getThreads(),
                        properties.getEffectiveTimeoutMs());
                if (piece.ok)
                {
                    for (SubtitleParser.Cue cue : piece.cues)
                    {
                        all.add(new SubtitleParser.Cue(cue.startMs + from, cue.endMs + from, cue.text));
                    }
                }
                else
                {
                    reason = piece.reason;
                }
            }
            if (!reason.isEmpty())
            {
                log.warn("分片转写失败 postId={} seg={} chunk={}/{} {}", postId, seg, i + 1, chunks, reason);
                notes.add("第 " + (i + 1) + "/" + chunks + " 片转写失败（" + reason + "）");
            }
            Files.deleteIfExists(part);
            subtitleMapper.updateProgress(postId, seg,
                    P_TRANSCRIBE_START + (int)((long)(P_TRANSCRIBE_END - P_TRANSCRIBE_START) * (i + 1) / chunks));
            log.info("分片转写 postId={} seg={} chunk={}/{} 累计cue={} 到={}ms", postId, seg, i + 1, chunks,
                    all.size(), all.isEmpty() ? 0 : all.get(all.size() - 1).endMs);
        }
        return all;
    }

    /** wav 时长：16k 单声道 pcm_s16le = 每秒 32000 字节（44 字节文件头）；估不出来返回 0 */
    private static long wavDurationMs(Path wav)
    {
        try
        {
            long bytes = Files.size(wav) - 44;
            return bytes > 0 ? bytes * 1000L / 32000L : 0L;
        }
        catch (IOException e)
        {
            return 0L;
        }
    }

    /** 原子落盘：先写临时文件再 move，避免前端读到半个 VTT */
    private void write(Path target, String text, Path work) throws IOException
    {
        Path parent = target.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(work, "out-", ".vtt");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void fail(Long postId, int seg, String reason)
    {
        subtitleMapper.markFailed(postId, seg, truncate(reason));
        log.info("字幕标记失败 postId={} seg={} reason={}", postId, seg, truncate(reason));
    }

    /**
     * 重签并返回第 seg 段地址（源站签名过期时用来续期）
     *
     * <p>与播放链路同一套 sidecar 协议；这里只为“重签一次再重试”服务，返回值用不上。</p>
     */
    private String resign(Long id, int seg)
    {
        String sourceUrl = postMapper.selectSourceUrl(id);
        if (sourceUrl == null || sourceUrl.isEmpty())
        {
            return null;
        }
        try
        {
            String body = "{\"id\":" + id + ",\"url\":\"" + sourceUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            long seconds = Long.getLong("qtools.resign.timeoutSec", 30L);
            HttpRequest req = HttpRequest.newBuilder(URI.create(SIDECAR + "/resign"))
                    .timeout(Duration.ofSeconds(seconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200)
            {
                log.warn("字幕重签 sidecar 返回 {} id={}", resp.statusCode(), id);
                return null;
            }
            JSONObject obj = JSON.parseObject(resp.body());
            if (obj == null)
            {
                return null;
            }
            Object rawUrls = obj.get("urls");
            if (rawUrls instanceof List<?> urls && seg >= 0 && seg < urls.size() && urls.get(seg) != null)
            {
                return String.valueOf(urls.get(seg));
            }
            Object one = obj.get("url");
            return one == null ? null : String.valueOf(one);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (Exception e)
        {
            log.warn("字幕重签失败 id={} {}", id, e.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------ 行 / 视图

    private Map<String, Object> row(Long postId, int seg)
    {
        Map<String, Object> row = subtitleMapper.selectOne(postId, seg);
        if (row == null)
        {
            subtitleMapper.insertIfAbsent(postId, seg);
            row = subtitleMapper.selectOne(postId, seg);
        }
        return row;
    }

    /** 前端契约（§T7）：status/progress/cueCount/durMs/err/src/updatedAt/engine */
    private Map<String, Object> data(Long postId, int seg, Map<String, Object> row)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        int status = row == null ? 0 : intOf(row.get("status"));
        boolean stale = row != null && stale(row);
        data.put("status", status == 1 && stale ? 0 : status);
        data.put("progress", row == null ? 0 : intOf(row.get("progress")));
        data.put("cueCount", row == null ? 0 : intOf(row.get("cueCount")));
        data.put("durMs", row == null ? 0L : longOf(row.get("durMs")));
        data.put("engine", row == null ? "" : text(row.get("engine")));
        data.put("err", row == null ? "" : text(row.get("err")));
        long epoch = row == null ? 0L : epochOf(row.get("updatedAt"));
        data.put("updatedAt", epoch);
        data.put("src", "/api/tool/web/subtitle/" + postId + "/" + seg + ".vtt?v=" + epoch);
        return data;
    }

    private Path finalPath(Long postId, int seg)
    {
        return Path.of(properties.getEffectiveDir(), String.valueOf(postId), seg + ".vtt");
    }

    private String key(Long postId, int seg)
    {
        return postId + ":" + seg;
    }

    private String engine()
    {
        SubtitleProperties.Translate cfg = properties.getTranslate();
        String name = properties.getWhisperModel() + "+" + (cfg.getModel() == null ? "" : cfg.getModel());
        return truncate(name, 64);
    }

    private static boolean stale(Map<String, Object> row)
    {
        Object value = row == null ? null : row.get("stale");
        if (value instanceof Number number)
        {
            return number.intValue() == 1;
        }
        return value instanceof Boolean flag && flag;
    }

    private static int intOf(Object value)
    {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long longOf(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private static long epochOf(Object value)
    {
        if (value instanceof LocalDateTime time)
        {
            return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.sql.Timestamp stamp)
        {
            return stamp.getTime();
        }
        if (value instanceof java.util.Date date)
        {
            return date.getTime();
        }
        return 0L;
    }

    private static String truncate(String value)
    {
        return truncate(value, ERR_MAX);
    }

    private static String truncate(String value, int max)
    {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static void deleteTree(Path dir)
    {
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored)
                {
                    // 删不掉就留给下次（临时目录，不影响结果）
                }
            });
        }
        catch (IOException ignored)
        {
            // 同上
        }
    }
}
