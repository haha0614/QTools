package com.qtools.media.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaScanJob;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaScanJobMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaScanService;

/**
 * 重建索引任务 服务实现
 *
 * <p>执行的是 {@code doc/media-import/import.js}（M1 已验收的迁移脚本），它是 upsert 语义：</p>
 * <ul>
 *   <li>绝不带 {@code --truncate}：带了对 media_* 先 DELETE，会清掉管理端的 hidden 状态，
 *       并且 media_favorite / media_recent 里的 target_id 会变成悬空引用；</li>
 *   <li>必须带 {@code --dir-mode=raw --allow-dup-dir}：默认 mark 模式会给共用同一物理目录的
 *       念念 144 本相册的 dir 追加 " #&lt;albumId&gt;"，等于每次扫描都改写 media_album.dir；</li>
 *   <li>{@code --out} 指到 uploadPath 下，脚本的 import.sql / report.md 不再写回仓库。</li>
 * </ul>
 *
 * <p>并发保护用「内存标记 + DB running 计数」双保险：内存标记挡住同 JVM 的并发请求，
 * DB 计数挡住进程重启后残留的 running 行。</p>
 *
 * @author qtools
 */
@Service
public class MediaScanServiceImpl implements IMediaScanService
{
    private static final Logger log = LoggerFactory.getLogger(MediaScanServiceImpl.class);

    private static final String STATUS_RUNNING = "running";

    private static final String STATUS_DONE = "done";

    private static final String STATUS_FAILED = "failed";

    /** 日志尾部保留行数（GET /scan/{id} 只回这么多行） */
    private static final int LOG_MAX_LINES = 200;

    /** media_scan_job.log 是 TEXT，留出余量避免超长被 MySQL 截断 */
    private static final int LOG_MAX_CHARS = 60_000;

    /** import.js 第一条输出：videos=176 albums=156 photos=9628 people=10（源数据条数） */
    private static final Pattern SOURCE_COUNTS =
            Pattern.compile("videos=(\\d+)\\s+albums=(\\d+)\\s+photos=(\\d+)\\s+people=(\\d+)");

    /** import.js 导入后回读：条数 videos=176 albums=156 photos=9628 people=10 ...（已落库条数） */
    private static final Pattern LOADED_COUNTS = Pattern.compile("条数 videos=(\\d+) albums=(\\d+) photos=(\\d+) people=(\\d+)");

    /** build_thumbs.js 首行：START tasks=videos,photos,previews total=10359 concurrency=6 force=0 */
    private static final Pattern THUMB_START = Pattern.compile("^START\\b.*\\btotal=(\\d+)");

    /** build_thumbs.js 进度：PROGRESS 25/10359 eta=12 */
    private static final Pattern THUMB_PROGRESS = Pattern.compile("^PROGRESS\\s+(\\d+)/(\\d+)");

    /** build_thumbs.js 收尾：DONE ok=12 skip=10347 fail=0 seconds=3 */
    private static final Pattern THUMB_DONE = Pattern.compile("^DONE\\b.*\\bok=(\\d+)\\s+skip=(\\d+)\\s+fail=(\\d+)");

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 允许的 mode 原子值 */
    private static final List<String> MODES = List.of("scan", "thumb", "sprite", "preview", "gc");

    /** 只能单独使用的 mode（sprite / preview 允许组合成 sprite,preview） */
    private static final List<String> SINGLE_MODES = List.of("scan", "thumb", "gc");

    /** limit 上限：176 个视频的真实规模下，超过这个数只可能是手滑 */
    private static final int MAX_LIMIT = 10000;

    /** 缩略图脚本的受管开关：一律由 buildThumbCommand 按 mode 追加，配置里写了也过滤 */
    private static final List<String> THUMB_MANAGED_ARGS =
            List.of("--force", "--sprite", "--preview", "--gc", "--yes", "-y");

    /** 扫描脚本的禁用参数：--truncate 会清掉 hidden 与收藏引用 */
    private static final List<String> SCAN_FORBIDDEN_ARGS = List.of("--truncate");

    @Autowired
    private MediaScanJobMapper mediaScanJobMapper;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaIndexService mediaIndexService;

    /** 同一时刻只有一个扫描线程，天然串行 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-scan");
        thread.setDaemon(true);
        return thread;
    });

    private final Object startLock = new Object();

    /** 本 JVM 正在跑的任务 id（重启后为 null，由 DB running 计数兜底） */
    private final AtomicLong runningJobId = new AtomicLong(0L);

    @PreDestroy
    public void shutdown()
    {
        executor.shutdownNow();
    }

    @Override
    public Map<String, Object> start(String type)
    {
        return start(type, null, false, null);
    }

    @Override
    public Map<String, Object> start(String type, String mode, boolean gcConfirm, Integer limit)
    {
        String jobType = normalizeMode(type, mode);
        if ("gc".equals(jobType) && !gcConfirm)
        {
            // 派生文件 GC 是真删：不能只靠前端弹窗，后端没有 confirm=true 一律拒绝
            throw MediaException.badRequest("mode=gc 需要显式 confirm=true（会真删 v/ s/ pv2/ 下的孤儿派生文件）");
        }
        int limited = normalizeLimit(jobType, limit);
        // 配置缺失必须在建 job 之前就报可读错误：否则子进程只会给 CreateProcess error=2 / NPE
        boolean thumb = isThumbScript(jobType);
        requireConfigured(mediaProperties.getNodePath(), "nodePath");
        requireConfigured(mediaProperties.getScanOutPath(), "scanOutPath");
        requireConfigured(thumb ? mediaProperties.getThumbScript() : mediaProperties.getScanScript(),
                thumb ? "thumbScript" : "scanScript");
        synchronized (startLock)
        {
            if (runningJobId.get() != 0L || mediaScanJobMapper.countRunning() > 0)
            {
                throw MediaException.conflict("已有扫描任务在运行，请等待结束后再触发");
            }
            MediaScanJob job = new MediaScanJob();
            job.setType(jobType);
            job.setStatus(STATUS_RUNNING);
            mediaScanJobMapper.insertJob(job);
            runningJobId.set(job.getId());
            log.info("管理端触发扫描任务 job={} mode={} limit={}", job.getId(), jobType, limited);
            executor.submit(() -> runJob(job.getId(), limited));
            return toView(mediaScanJobMapper.selectJobById(job.getId()));
        }
    }

    @Override
    public Map<String, Object> latest()
    {
        return toView(mediaScanJobMapper.selectLatest());
    }

    @Override
    public Map<String, Object> getJob(long jobId)
    {
        MediaScanJob job = mediaScanJobMapper.selectJobById(jobId);
        if (job == null)
        {
            throw MediaException.notFound("scan job not found: " + jobId);
        }
        return toView(job);
    }

    @Override
    public List<Map<String, Object>> recent(int limit)
    {
        int size = limit <= 0 ? 10 : Math.min(limit, 50);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaScanJob job : mediaScanJobMapper.selectRecentJobs(size))
        {
            out.add(toView(job));
        }
        return out;
    }

    // ------------------------------------------------------------------ 执行

    private void runJob(Long jobId, int limit)
    {
        MediaScanJob job = mediaScanJobMapper.selectJobById(jobId);
        // type 落的就是 normalizeMode 的归一化结果：full 走 import.js，其余（thumb / sprite / preview /
        // sprite,preview / gc）全部走 build-thumbs.js，只有子进程参数不同
        String jobType = job == null || StringUtils.isEmpty(job.getType()) ? "full" : job.getType();
        boolean thumb = isThumbScript(jobType);
        long begin = System.currentTimeMillis();
        Deque<String> tail = new ArrayDeque<>();
        // counters[0]=total, counters[1]=done（full 取脚本回读条数；thumb 取 START/PROGRESS 行）
        int[] counters = new int[2];
        int exit = -1;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(thumb ? buildThumbCommand(jobType, limit) : buildCommand());
            // 子进程工作目录必须先存在（import.js 会把 import.sql / report.md 写进去）
            String outPath = mediaProperties.getScanOutPath();
            requireConfigured(outPath, "scanOutPath");
            File outDir = new File(outPath);
            Files.createDirectories(outDir.toPath());
            builder.directory(outDir);
            builder.redirectErrorStream(true);
            if (thumb)
            {
                applyThumbEnv(builder);
            }
            // 两种模式都可能读库（import.js / scan-media.js / build-thumbs.js），mysql 不在 PATH 时必须靠 MS_MYSQL
            applyMysqlEnv(builder);
            appendLine(tail, "== 开始执行 " + String.join(" ", builder.command()));
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                int counter = 0;
                while ((line = reader.readLine()) != null)
                {
                    // PROGRESS 行的信息量已进 total/done 两列：1 万张缩略图会产生 400+ 行，
                    // 全存进日志尾部会把 START/TASK/DONE 挤出去（无法判断是否增量）
                    if (!(thumb && line.startsWith("PROGRESS ")))
                    {
                        appendLine(tail, line);
                    }
                    if (thumb)
                    {
                        parseThumbLine(line, counters);
                    }
                    else
                    {
                        parseFullLine(line, counters);
                    }
                    if (++counter % 10 == 0)
                    {
                        mediaScanJobMapper.updateProgress(jobId, counters[0], counters[1]);
                    }
                }
            }
            int timeout = Math.max(1, timeoutMinutesOf(jobType));
            if (!process.waitFor(timeout, TimeUnit.MINUTES))
            {
                process.destroyForcibly();
                appendLine(tail, "== 超时（" + timeout + " 分钟），已强杀子进程");
                exit = -2;
            }
            else
            {
                exit = process.exitValue();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            appendLine(tail, "== 线程被中断");
            exit = -3;
        }
        catch (Exception e)
        {
            log.error("扫描任务异常 job={}", jobId, e);
            appendLine(tail, "== 执行异常：" + e);
            exit = -1;
        }

        String status = exit == 0 ? STATUS_DONE : STATUS_FAILED;
        if (STATUS_DONE.equals(status) && counters[1] == 0)
        {
            counters[1] = counters[0];
        }
        long cost = System.currentTimeMillis() - begin;
        appendLine(tail, "== 结束 status=" + status + " exit=" + exit + " total=" + counters[0] + " done=" + counters[1]
                + " 耗时=" + cost + "ms");
        mediaScanJobMapper.finishJob(jobId, status, counters[0], counters[1], joinTail(tail), new Date());
        runningJobId.set(0L);
        // 扫描改动了 media_* 或缩略图，索引快照必须失效，否则用户端 60s 内看不到新数据
        mediaIndexService.invalidateSnapshot();
        log.info("扫描结束 job={} mode={} status={} total={} done={} cost={}ms", jobId,
                jobType, status, counters[0], counters[1], cost);
    }

    /** full：total 取源数据条数行，done 取落库回读行 */
    private static void parseFullLine(String line, int[] counters)
    {
        Integer sourceTotal = sumCounts(SOURCE_COUNTS, line);
        if (sourceTotal != null)
        {
            counters[0] = sourceTotal;
        }
        Integer loaded = sumCounts(LOADED_COUNTS, line);
        if (loaded != null)
        {
            counters[1] = loaded;
        }
    }

    /** thumb：total 取 START/DONE 行，done 取 PROGRESS 行 */
    private static void parseThumbLine(String line, int[] counters)
    {
        Matcher start = THUMB_START.matcher(line);
        if (start.find())
        {
            counters[0] = Integer.parseInt(start.group(1));
            return;
        }
        Matcher progress = THUMB_PROGRESS.matcher(line);
        if (progress.find())
        {
            counters[1] = Integer.parseInt(progress.group(1));
            counters[0] = Integer.parseInt(progress.group(2));
            return;
        }
        Matcher done = THUMB_DONE.matcher(line);
        if (done.find())
        {
            int sum = Integer.parseInt(done.group(1)) + Integer.parseInt(done.group(2))
                    + Integer.parseInt(done.group(3));
            counters[1] = sum;
            if (counters[0] == 0)
            {
                counters[0] = sum;
            }
        }
    }

    /** node 可执行文件：未配置或不存在都给可读错误（子进程报的 CreateProcess error=2 定位不到配置项） */
    private String requireNode()
    {
        String node = mediaProperties.getNodePath();
        requireConfigured(node, "nodePath");
        if (!new File(node).isFile())
        {
            throw new MediaException(500, "qtools.media.nodePath 不存在: " + node);
        }
        return node;
    }

    /** 脚本文件：未配置或不存在都给可读错误 */
    private static String requireScript(String script, String key)
    {
        requireConfigured(script, key);
        if (!new File(script).isFile())
        {
            throw new MediaException(500, "qtools.media." + key + " 不存在: " + script);
        }
        return script;
    }

    /** 必填配置校验：空白视为未配置，错误里带上环境变量覆盖名，方便照着配 */
    private static void requireConfigured(String value, String key)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new MediaException(500, "未配置 qtools.media." + key + "（请在 application.yml 或环境变量 "
                    + envNameOf(key) + " 中提供）");
        }
    }

    /** qtools.media.scanOutPath → QTOOLS_MEDIA_SCANOUTPATH（与 Spring 宽松绑定一致） */
    private static String envNameOf(String key)
    {
        return ("qtools.media." + key).replace('.', '_').toUpperCase();
    }

    private String[] buildCommand()
    {
        List<String> command = new ArrayList<>();
        command.add(requireNode());
        command.add(requireScript(mediaProperties.getScanScript(), "scanScript"));
        command.add("--out=" + mediaProperties.getScanOutPath());
        appendConfiguredArgs(command, mediaProperties.getScanArgs(), SCAN_FORBIDDEN_ARGS);
        return command.toArray(new String[0]);
    }

    /**
     * 缩略图 / 生成类命令：{@code node build-thumbs.js [配置参数] [--sprite] [--preview] [--gc --yes] [--limit=n]}
     *
     * <p>脚本自带增量判定（按源文件 mtime 跳过已存在的产物），默认参数就是增量模式。</p>
     *
     * <p>生成开关（{@code --sprite} / {@code --preview}）与删除开关（{@code --gc --yes}）
     * <b>只在本方法内按 mode 追加</b>：配置里写了同类开关会被过滤（见 {@link #THUMB_MANAGED_ARGS}），
     * 前端也传不进任意参数——mode 只有白名单里的 5 个原子值。</p>
     */
    private String[] buildThumbCommand(String jobType, int limit)
    {
        String script = requireScript(mediaProperties.getThumbScript(), "thumbScript");
        List<String> command = new ArrayList<>();
        command.add(requireNode());
        command.add(script);
        appendConfiguredArgs(command, mediaProperties.getThumbArgs(), THUMB_MANAGED_ARGS);
        // jobType 是 normalizeMode 归一化后的逗号拼接值：sprite,preview 两个开关都会带上
        if (jobType.contains("sprite"))
        {
            command.add("--sprite");
        }
        if (jobType.contains("preview"))
        {
            command.add("--preview");
        }
        if ("gc".equals(jobType))
        {
            // --yes 由后端追加：缺了它脚本只干跑统计，不会真删
            command.add("--gc");
            command.add("--yes");
        }
        if (limit > 0)
        {
            command.add("--limit=" + limit);
        }
        return command.toArray(new String[0]);
    }

    /**
     * mode 归一化：{@code scan | thumb | sprite | preview | gc | sprite,preview}
     *
     * <p>组合模式只接受<b>逗号分隔</b>这一种写法（{@code sprite,preview}），不额外接受
     * {@code spritepreview} 之类的缩写——一种写法便于文档、日志与运维检索；token 顺序无关，
     * 落库与日志统一写成 {@code sprite,preview}，重复 token 去重而不报错。</p>
     *
     * @param type 旧参数：mode 为空时兜底（full → scan，thumb → thumb）
     * @param mode 前端传入的 mode
     * @return 落库到 {@code media_scan_job.type} 的值：scan → {@code full}（沿用旧值），其余为 mode 本身
     */
    private static String normalizeMode(String type, String mode)
    {
        String raw = StringUtils.isEmpty(mode) ? defaultModeOf(type) : mode.trim().toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String part : raw.split(","))
        {
            String token = part.trim();
            if (token.isEmpty())
            {
                continue;
            }
            if (!MODES.contains(token))
            {
                throw MediaException.badRequest("mode 不支持：" + token + "（可用 " + String.join(" / ", MODES)
                        + "，组合模式写成 sprite,preview）");
            }
            if (!tokens.contains(token))
            {
                tokens.add(token);
            }
        }
        if (tokens.isEmpty())
        {
            throw MediaException.badRequest("mode 不能为空（可用 " + String.join(" / ", MODES) + " 或 sprite,preview）");
        }
        if (tokens.size() > 1)
        {
            for (String single : SINGLE_MODES)
            {
                if (tokens.contains(single))
                {
                    throw MediaException.badRequest("mode=" + single + " 不能与其他 mode 组合（组合只允许 sprite,preview）");
                }
            }
        }
        if (tokens.contains("scan"))
        {
            return "full";
        }
        if (tokens.contains("thumb"))
        {
            return "thumb";
        }
        if (tokens.contains("gc"))
        {
            return "gc";
        }
        List<String> generate = new ArrayList<>();
        for (String name : List.of("sprite", "preview"))
        {
            if (tokens.contains(name))
            {
                generate.add(name);
            }
        }
        return String.join(",", generate);
    }

    /** 旧调用只有 type：thumb → thumb，其余（含空）→ scan */
    private static String defaultModeOf(String type)
    {
        return "thumb".equals(StringUtils.isEmpty(type) ? "" : type.trim()) ? "thumb" : "scan";
    }

    /** full（import.js）之外的 mode 一律执行 thumbScript（build-thumbs.js） */
    private static boolean isThumbScript(String jobType)
    {
        return !"full".equals(jobType);
    }

    /** limit 只对逐条生成的 mode 生效；scan / gc 传了直接 400，避免误以为已经「试跑过」 */
    private static int normalizeLimit(String jobType, Integer limit)
    {
        if (limit == null)
        {
            return 0;
        }
        if (limit < 0 || limit > MAX_LIMIT)
        {
            throw MediaException.badRequest("limit 必须是 0 ~ " + MAX_LIMIT + " 的整数（0 或留空 = 不限）");
        }
        if (limit > 0 && ("full".equals(jobType) || "gc".equals(jobType)))
        {
            throw MediaException.badRequest("mode=" + ("full".equals(jobType) ? "scan" : jobType) + " 不支持 limit");
        }
        return limit;
    }

    /** 超时预算：扫描 10 分钟、缩略图 30 分钟，精灵图/预览/GC 走 deriveTimeoutMinutes（默认 12 小时） */
    private int timeoutMinutesOf(String jobType)
    {
        if ("full".equals(jobType))
        {
            return mediaProperties.getScanTimeoutMinutes();
        }
        if ("thumb".equals(jobType))
        {
            return mediaProperties.getThumbTimeoutMinutes();
        }
        return mediaProperties.getDeriveTimeoutMinutes();
    }

    /** 追加配置里的附加参数：跳过空白与被禁用的受管开关 */
    private static void appendConfiguredArgs(List<String> command, String extra, List<String> forbidden)
    {
        if (StringUtils.isEmpty(extra))
        {
            return;
        }
        for (String arg : extra.trim().split("\\s+"))
        {
            if (arg.isEmpty() || forbidden.contains(arg))
            {
                continue;
            }
            command.add(arg);
        }
    }

    /** build_thumbs.js 认 MS_FFMPEG；用它替代脚本内默认的 runtime/ffmpeg.exe */
    private void applyThumbEnv(ProcessBuilder builder)
    {
        String ffmpeg = mediaProperties.getFfmpeg();
        if (StringUtils.isNotEmpty(ffmpeg))
        {
            builder.environment().put("MS_FFMPEG", ffmpeg);
        }
    }

    /**
     * 脚本认 MS_MYSQL（优先级 {@code --mysql=} &gt; {@code MS_MYSQL} &gt; {@code PATH}）：
     * mysql 不在 PATH 的机器上不注入就会读库失败（media_video / media_photo 拿不到 id / rel_path / content_hash）。
     *
     * <p>只有 {@code qtools.media.mysqlClient} 非空才注入，留空沿用旧行为（脚本自己找 PATH），
     * 不影响已有部署与环境变量本已存在的 MS_MYSQL。</p>
     */
    private void applyMysqlEnv(ProcessBuilder builder)
    {
        String mysql = mediaProperties.getMysqlClient();
        if (mysql == null || mysql.trim().isEmpty())
        {
            return;
        }
        builder.environment().put("MS_MYSQL", mysql.trim());
    }

    /** 从一行输出里解析 4 个条数并求和；不匹配返回 null */
    private static Integer sumCounts(Pattern pattern, String line)
    {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find())
        {
            return null;
        }
        int sum = 0;
        for (int i = 1; i <= 4; i++)
        {
            sum += Integer.parseInt(matcher.group(i));
        }
        return sum;
    }

    private static void appendLine(Deque<String> tail, String line)
    {
        tail.addLast(line);
        while (tail.size() > LOG_MAX_LINES)
        {
            tail.removeFirst();
        }
    }

    private static String joinTail(Deque<String> tail)
    {
        StringBuilder sb = new StringBuilder();
        for (String line : tail)
        {
            if (sb.length() + line.length() + 1 > LOG_MAX_CHARS)
            {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 视图

    private Map<String, Object> toView(MediaScanJob job)
    {
        Map<String, Object> view = new LinkedHashMap<>();
        if (job == null)
        {
            view.put("id", null);
            view.put("status", null);
            return view;
        }
        view.put("id", job.getId());
        view.put("jobId", job.getId());
        view.put("type", job.getType());
        view.put("status", job.getStatus());
        view.put("total", job.getTotal());
        view.put("done", job.getDone());
        view.put("startedAt", formatDate(job.getStartedAt()));
        view.put("finishedAt", formatDate(job.getFinishedAt()));
        if (job.getFinishedAt() != null && job.getStartedAt() != null)
        {
            view.put("costMs", job.getFinishedAt().getTime() - job.getStartedAt().getTime());
        }
        view.put("logTail", tailLines(job.getLog()));
        return view;
    }

    private static List<String> tailLines(String logText)
    {
        List<String> lines = new ArrayList<>();
        if (StringUtils.isEmpty(logText))
        {
            return lines;
        }
        String[] all = logText.split("\n");
        int from = Math.max(0, all.length - LOG_MAX_LINES);
        for (int i = from; i < all.length; i++)
        {
            if (!all[i].isEmpty())
            {
                lines.add(all[i]);
            }
        }
        return lines;
    }

    private static String formatDate(Date date)
    {
        return date == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, date);
    }
}
