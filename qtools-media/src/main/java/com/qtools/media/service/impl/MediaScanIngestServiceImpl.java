package com.qtools.media.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaIndexSnapshot;
import com.qtools.media.domain.dto.MediaScanIngestRequest;
import com.qtools.media.domain.dto.MediaScanRequest;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaScanJobMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaMaintenanceService;
import com.qtools.media.service.IMediaScanIngestService;
import com.qtools.media.service.IMediaScanService;
import com.qtools.media.support.MediaScanArgs;
import com.qtools.media.support.MediaScanGate;
import com.qtools.media.support.MediaScanPreviewSupport;
import com.qtools.media.support.MediaToolEnv;

/**
 * 扫描入库 服务实现（{@code node doc/media-import/scan-media.js --root=<库根> --root-id=<id>}）
 *
 * <p>为什么单独一套执行器而不复用 {@code MediaScanServiceImpl#mode=scan}：那个 mode 的媒体根写死在
 * {@code qtools.media.scanArgs} 里（部署期一个根），而且任务只在结束时把日志尾部落
 * {@code media_scan_job.log}——前端要的「实时日志 + 进度 + 取消」它给不了。这里保持
 * <b>任务状态纯内存</b>：{@code ConcurrentHashMap} + 每任务 2000 行环形缓冲，最近 10 个任务可查。</p>
 *
 * <p>三个安全边界：</p>
 * <ul>
 *   <li>媒体根只能来自 {@code media_root.path}（{@code rootId} 必须存在且 {@code kind=root}），
 *       请求里没有任何路径字段可传；</li>
 *   <li>子进程开关一律由后端按请求追加，配置 {@code scanArgs} 里的同类参数被过滤（见 {@link MediaScanArgs}）；</li>
 *   <li>同一时刻只允许一个 node 子进程：与维护任务共用 {@link MediaScanGate#lock}，
 *       另外还查一次 {@code media_scan_job} 的 running 行（进程重启后内存锁是空的，DB 计数不是）。</li>
 * </ul>
 *
 * @author qtools
 */
@Service
public class MediaScanIngestServiceImpl implements IMediaScanIngestService
{
    private static final Logger log = LoggerFactory.getLogger(MediaScanIngestServiceImpl.class);

    private static final String STATUS_RUNNING = "running";

    private static final String STATUS_DONE = "done";

    private static final String STATUS_FAILED = "failed";

    private static final String STATUS_CANCELLED = "cancelled";

    /** 每个任务的日志环形缓冲（2000 行 ≈ 一次大目录扫描的告警量；前端只显示最后 500 行） */
    private static final int LOG_MAX_LINES = 2000;

    /** 内存里最多保留多少个任务（含正在跑的）；更早的直接查不到（404） */
    private static final int JOBS_KEPT = 10;

    /** limit 上限：与维护任务的 MAX_LIMIT 同口径 */
    private static final int MAX_LIMIT = 10000;

    /** 取消后等子进程退出的时间（超时再 destroyForcibly） */
    private static final long CANCEL_GRACE_MILLIS = 3000L;

    /** 取消接口等任务线程把状态落下来的上限：超过就照实回 running（前端会继续轮询） */
    private static final long CANCEL_WAIT_MILLIS = 5000L;

    /** 脚本第一行条数：{@code videos=3 albums=1 photos=2 people=0}（磁盘上的源数据） */
    private static final Pattern COUNTS = Pattern.compile("^videos=(\\d+)\\s+albums=(\\d+)\\s+photos=(\\d+)");

    /** 脚本收尾统计里的「跳过」：{@code 新插入=3 移动=0 更新=0 重复=0 跳过=0 别名=0 missing=0 revived=-} */
    private static final Pattern SKIPPED = Pattern.compile("跳过=(\\d+)");

    /** backfill-hash 收尾行：{@code 回填 videos=3 photos=2 missing=0}（两个 0 = 没有要补的行） */
    private static final Pattern BACKFILL = Pattern.compile("回填\\s+videos=(\\d+)\\s+photos=(\\d+)");

    /** 环形缓冲里的下标（counters 用一个 int[] 省一次对象分配：任务可能每秒被读几十次） */
    private static final int C_VIDEOS = 0;

    private static final int C_ALBUMS = 1;

    private static final int C_PHOTOS = 2;

    private static final int C_SKIPPED = 3;

    private static final int C_ERRORS = 4;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /* ------------------------------------------------------------------ 流水线阶段（P5.97）
       「扫描入库」不再是一个动作，而是一条链：扫盘写库 → 需要时补 content_hash → 补元数据(ffprobe)
       → 生成封面/缩略图 →（可选）精灵图·预览片段 → 重建索引快照。
       每一段都是可单独重试的阶段：阶段键稳定（前端按 key 打状态），阶段动作全部幂等，
       所以「失败后只重试那一步」不会重复写库、也不会重复解码已是最新的产物。 */

    /** 扫盘入库（本类自己起子进程，实时日志） */
    public static final String PHASE_SCAN = "scan";

    /** 补 content_hash：只补空值；扫描已写过就整段跳过 */
    public static final String PHASE_HASH = "hash";

    /** 补元数据 ffprobe（P5.96：dur / fps / w / h） */
    public static final String PHASE_META = "meta";

    /** 封面 / 缩略图（build-thumbs.js mode=thumb） */
    public static final String PHASE_THUMB = "thumb";

    /** 精灵图 + 预览片段（可选项，全片解码，慢两个数量级） */
    public static final String PHASE_SPRITE = "sprite";

    /** 重建索引快照（站端下一次请求就能看到新内容） */
    public static final String PHASE_INDEX = "index";

    private static final String PHASE_PENDING = "pending";

    private static final String PHASE_RUNNING = "running";

    private static final String PHASE_DONE = "done";

    private static final String PHASE_FAILED = "failed";

    private static final String PHASE_SKIPPED = "skipped";

    private static final String PHASE_CANCELLED = "cancelled";

    /** derive 取值：不生成派生资源 / 只生成封面缩略图（默认） / 再加精灵图与预览片段 */
    private static final String DERIVE_NONE = "none";

    private static final String DERIVE_THUMB = "thumb";

    private static final String DERIVE_FULL = "full";

    /** 后续阶段的轮询间隔：维护任务把进度写库，1s 一次足够且不压库 */
    private static final long PHASE_POLL_MILLIS = 1000L;

    /** 预演子进程超时（秒）：dry-run 只走盘不读库，比真实扫描短得多 */
    private static final long PREVIEW_TIMEOUT_SECONDS = 180L;


    /** 扫描脚本永远不接受的参数（--truncate 会清 6 张 media_* 表） */
    private static final List<String> SCAN_FORBIDDEN_ARGS =
            List.of("--truncate", "--dry-run", "--only-video", "--only-photo", "--purge-unclaimed");

    /** 只能由请求决定的开关：配置里写了也丢掉，避免「按钮点的是视频、配置里偷偷扫了图片」 */
    private static final List<String> SCAN_MANAGED_ARGS = List.of("--backfill-hash", "--fix-poster-path",
            "--backfill-album-mtime", "--prune-missing");

    /**
     * 请求已经给了值的前缀参数：配置里的同名项必须让位
     *
     * <p>{@code --db= / --host= / --port=} 也在这里：扫描入库一律连<b>后端自己的数据源</b>
     * （见 {@link #datasourceTarget()}），配置里那一份是维护任务用的，两边同时在场会分叉。</p>
     */
    private static final List<String> SCAN_FORBIDDEN_PREFIXES = List.of("--root=", "--root-id=", "--limit=",
            "--db=", "--host=", "--port=");

    /**
     * 补元数据阶段让位的参数前缀：媒体根 / 库根 / limit / 连接参数一律由后端按任务落，
     * 配置（enrichArgs）里那一份是维护面板用的，两边同时在场会分叉
     */
    private static final List<String> ENRICH_FORBIDDEN_PREFIXES = List.of("--root=", "--root-id=", "--limit=",
            "--db=", "--host=", "--port=");

    /** enrich-meta.js 的每根校验行：{@code VERIFY root_id=3 total=63 missing_dur=0 missing_fps=0} */
    private static final Pattern VERIFY = Pattern.compile(
            "^VERIFY\\s+root_id=(\\d+)\\s+total=(\\d+)\\s+missing_dur=(\\d+)\\s+missing_fps=(\\d+)");

    /** 数据源目标（host / port / db）允许的字符：它会被拼成命令行参数，绝不能带空白或引号 */
    private static final Pattern TARGET_PART = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaIndexService mediaIndexService;

    @Autowired
    private IMediaMaintenanceService mediaMaintenanceService;

    @Autowired
    private MediaScanJobMapper mediaScanJobMapper;

    @Autowired
    private MediaScanGate mediaScanGate;

    /**
     * 维护任务执行器：流水线后续阶段（补哈希 / 缩略图 / 精灵图预览）<b>整段交给它跑</b>，
     * 不自己拼命令行——命令白名单、超时、MS_MYSQL / MS_FFPROBE 注入、进度解析都在那边，
     * 复制一份必然分叉。每个后续阶段因此都会在 {@code media_scan_job} 留一条自己的任务行，
     * 维护面板照样能查到它的完整日志。
     */
    @Autowired
    private IMediaScanService mediaScanService;

    /**
     * 后端自己的 JDBC URL：子进程必须连<b>同一个库</b>
     *
     * <p>踩过的坑：{@code scanArgs} 里的 {@code --secret=} 只带口令、不带库名，脚本默认连
     * {@code qtools}。管理端跑在克隆库 / 灾备库 / 多实例环境时，父进程读的是 A 库、子进程却把扫描结果写进
     * B 库（生产库），而接口返回的 counters 一切正常——这是最难发现的错法。所以这里把数据源 URL 里的
     * host / port / db 显式传下去，覆盖配置里的同名参数。</p>
     */
    @Value("${spring.datasource.druid.master.url:}")
    private String datasourceUrl;

    /** 同一时刻只有一个扫描进程，天然串行 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-scan-ingest");
        thread.setDaemon(true);
        return thread;
    });

    /** 超时看门狗：读日志的线程阻塞在 readLine 上，光靠它自己数不到 30 分钟 */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-scan-ingest-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicLong sequence = new AtomicLong();

    /** 任务表：按插入顺序淘汰最老的（最新那个可能正 running，淘汰时会再确认一次状态） */
    private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<String, Job>(16, 0.75f, false)
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Job> eldest)
        {
            return size() > JOBS_KEPT && !STATUS_RUNNING.equals(eldest.getValue().status);
        }
    });

    @PreDestroy
    public void shutdown()
    {
        executor.shutdownNow();
        watchdog.shutdownNow();
    }

    // ------------------------------------------------------------------ 启动

    @Override
    public Map<String, Object> start(MediaScanIngestRequest request)
    {
        MediaScanIngestRequest req = request == null ? new MediaScanIngestRequest() : request;
        long rootId = req.getRootId() == null ? 0L : req.getRootId().longValue();
        if (rootId <= 0L)
        {
            throw MediaException.badRequest("rootId 必填（media_root.id，正整数）");
        }
        Map<String, Object> root = requireScanRoot(rootId);
        String rootPath = String.valueOf(root.get("path"));
        boolean dryRun = Boolean.TRUE.equals(req.getDryRun());
        boolean withVideo = req.getWithVideo() == null || Boolean.TRUE.equals(req.getWithVideo());
        boolean withPhoto = req.getWithPhoto() == null || Boolean.TRUE.equals(req.getWithPhoto());
        if (!withVideo && !withPhoto)
        {
            throw MediaException.badRequest("withVideo 与 withPhoto 不能同时为 false：没有要扫的内容");
        }
        int limit = normalizeLimit(req.getLimit());
        boolean force = Boolean.TRUE.equals(req.getForce());
        // P5.97：默认「一次点击跑完整条链」。dry-run 只是预演，绝不串后续阶段（它连库都不连）
        boolean pipeline = !dryRun && (req.getPipeline() == null || Boolean.TRUE.equals(req.getPipeline()));
        boolean wantHash = pipeline && (req.getHash() == null || Boolean.TRUE.equals(req.getHash()));
        boolean wantIndex = pipeline && (req.getRebuildIndex() == null || Boolean.TRUE.equals(req.getRebuildIndex()));
        String derive = normalizeDerive(req.getDerive(), pipeline);
        List<Phase> phases = planPhases(pipeline, wantHash, wantIndex, derive, withVideo);
        String[] command = buildCommand(rootPath, rootId, dryRun, withVideo, withPhoto, limit, force);
        // 配置缺失 / 脚本不存在都必须在建任务之前报可读错误：否则子进程只会给 CreateProcess error=2
        requireOutDir();
        synchronized (mediaScanGate.lock)
        {
            if (mediaScanGate.isIngestRunning())
            {
                throw MediaException.conflict("已有扫描入库任务在运行（" + mediaScanGate.getIngestJobId()
                        + "），请等待结束或先取消");
            }
            // 流水线的阶段间隙同样要挡人：不然别人此刻点「重建缩略图」就能插进来，两个子进程同时写库
            if (mediaScanGate.isPipelineRunning())
            {
                throw MediaException.conflict("已有扫描入库流水线在运行（" + mediaScanGate.getPipelineJobId()
                        + "），请等待结束或先取消");
            }
            if (mediaScanJobMapper.countRunning() > 0)
            {
                throw MediaException.conflict("已有扫描 / 维护任务在运行（重建索引、缩略图等），请等待结束后再扫描入库");
            }
            String jobId = "scn-" + sequence.incrementAndGet();
            Job job = new Job(jobId, rootId, String.valueOf(root.get("name")), rootPath, command, dryRun,
                    withVideo, withPhoto, limit, force, pipeline, derive, phases);
            jobs.put(jobId, job);
            mediaScanGate.ingestStarted(jobId);
            mediaScanGate.pipelineStarted(jobId);
            log.info("扫描入库启动 job={} rootId={} root={} dryRun={} withVideo={} withPhoto={} limit={} force={} pipeline={} derive={} phases={}",
                    jobId, rootId, rootPath, dryRun, withVideo, withPhoto, limit, force, pipeline, derive,
                    phaseKeys(phases));
            executor.submit(() -> runJob(job));
            return view(job, 0);
        }
    }

    @Override
    public Map<String, Object> active()
    {
        String jobId = mediaScanGate.getIngestJobId();
        Job job = jobId == null ? null : jobs.get(jobId);
        return job == null ? null : view(job, LOG_MAX_LINES);
    }

    @Override
    public Map<String, Object> getJob(String jobId, Integer tail)
    {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null)
        {
            throw MediaException.notFound("scan job not found: " + jobId + "（扫描入库任务只存在内存，进程重启后查不到）");
        }
        int size = tail == null || tail <= 0 ? LOG_MAX_LINES : Math.min(tail, LOG_MAX_LINES);
        return view(job, size);
    }

    @Override
    public Map<String, Object> cancel(String jobId)
    {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null)
        {
            throw MediaException.notFound("scan job not found: " + jobId);
        }
        if (!STATUS_RUNNING.equals(job.status))
        {
            throw MediaException.conflict("任务已结束（status=" + job.status + "），无法取消");
        }
        job.cancelRequested = true;
        // 当前阶段若是维护任务（补哈希 / 缩略图 / 精灵图预览），协作式取消要落到那个任务上；
        // 扫描阶段仍是「destroy 自己的子进程」这条老路
        String childJobId = job.childJobId;
        if (childJobId != null && job.process == null)
        {
            record(job, "== 收到取消请求：取消当前阶段的维护任务 #" + childJobId);
            try
            {
                mediaScanService.cancel(Long.parseLong(childJobId));
            }
            catch (Exception e)
            {
                record(job, "WARN 取消维护任务 #" + childJobId + " 失败：" + e.getMessage());
            }
        }
        Process process = job.process;
        if (process != null)
        {
            record(job, "== 收到取消请求：destroy 子进程（pid=" + pidOf(process) + "）");
            process.destroy();
            try
            {
                if (!process.waitFor(CANCEL_GRACE_MILLIS, TimeUnit.MILLISECONDS))
                {
                    record(job, "== 子进程 " + CANCEL_GRACE_MILLIS + "ms 内未退出，destroyForcibly");
                    process.destroyForcibly();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // 等任务线程把 status 落成 cancelled：读日志的线程要等流关闭，通常几十毫秒
        long deadline = System.currentTimeMillis() + CANCEL_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline && STATUS_RUNNING.equals(job.status))
        {
            try
            {
                Thread.sleep(50L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return view(job, LOG_MAX_LINES);
    }

    // ------------------------------------------------------------------ 执行

    private void runJob(Job job)
    {
        long begin = System.currentTimeMillis();
        Integer exit = runScanPhase(job);
        if (exit != null && exit == 0 && !job.cancelRequested)
        {
            runFollowUps(job);
        }
        else
        {
            skipPhasesFrom(job, PHASE_HASH, job.cancelRequested ? PHASE_CANCELLED : PHASE_SKIPPED,
                    job.cancelRequested ? "任务已取消，后续阶段未执行" : "扫描阶段未成功，后续阶段未执行");
        }
        finish(job, exit, begin);
    }

    /**
     * 扫描阶段：起 {@code scan-media.js} 子进程，日志逐行收进环形缓冲
     *
     * @return 子进程退出码（null = 没拿到退出码 / 线程被中断）
     */
    private Integer runScanPhase(Job job)
    {
        Phase phase = job.phaseOf(PHASE_SCAN);
        beginPhase(job, phase);
        Process process = null;
        ScheduledFuture<?> timeoutTask = null;
        Integer exit = null;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(job.command);
            builder.directory(requireOutDir());
            // 脚本的 WARN 全部走 stderr：合并进同一个流，日志区才看得到「已忽略参数 / 哈希失败」
            builder.redirectErrorStream(true);
            applyToolEnv(builder);
            process = builder.start();
            job.process = process;
            final Process running = process;
            int minutes = timeoutMinutes();
            timeoutTask = watchdog.schedule(() -> {
                if (STATUS_RUNNING.equals(job.status) && !job.finished)
                {
                    job.timedOut = true;
                    record(job, "== 超时（" + minutes + " 分钟），正在强杀子进程（pid=" + pidOf(running) + "）");
                    running.destroyForcibly();
                }
            }, minutes, TimeUnit.MINUTES);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    record(job, line);
                }
            }
            // 流关闭后子进程通常立刻退出；给 10s 缓冲（写库收尾 / 事务提交）
            if (running.waitFor(10, TimeUnit.SECONDS))
            {
                exit = running.exitValue();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            record(job, "== 线程被中断");
        }
        catch (Exception e)
        {
            log.error("扫描入库异常 job={}", job.jobId, e);
            record(job, "== 执行异常：" + e);
        }
        finally
        {
            if (timeoutTask != null)
            {
                timeoutTask.cancel(false);
            }
        }
        // 扫描阶段结束：先把「入库中」标记还回去——后续阶段要自己起子任务，被自己挡住就说不过去了；
        // 流水线标记继续在位（见 MediaScanGate#pipelineStarted 的注释），阶段之间别人照样插不进来
        String status = job.cancelRequested ? PHASE_CANCELLED
                : (exit != null && exit == 0 ? PHASE_DONE : PHASE_FAILED);
        endPhase(job, phase, status, exit == null ? "没拿到退出码" : ("exit=" + exit));
        mediaScanGate.ingestFinished(job.jobId);
        return exit;
    }

    /**
     * 补元数据阶段：{@code node enrich-meta.js ... --root-from-db --root-id=<n> --root=<本根>}
     *
     * <p>只处理<b>本库根</b>的待补行（root_id = n；n 是默认根时连带 root_id IS NULL 的存量行），
     * 扫完立刻跑，99% 情况下 dur / fps / w / h 当场补齐。脚本收尾打 {@code VERIFY root_id=.. total=..
     * missing_dur=..}，后端按它判 ffprobe 有没有生效：missing_dur &gt; 0 就在日志里明确报警
     * （不是静默失败，也不是让整个扫描任务报错——数据行已经进库了，缺的是元数据）。</p>
     */
    private void enrichAfterScan(Job job)
    {
        record(job, "== 扫描写库完成，开始补元数据（ffprobe）：root_id=" + job.rootId + " root=" + job.rootPath);
        Process process = null;
        ScheduledFuture<?> timeoutTask = null;
        Integer exit = null;
        try
        {
            String[] command = buildEnrichCommand(job);
            record(job, "== 开始执行 " + String.join(" ", command));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(requireOutDir());
            builder.redirectErrorStream(true);
            applyToolEnv(builder);
            process = builder.start();
            job.process = process;
            final Process running = process;
            int minutes = Math.max(1, mediaProperties.getEnrichTimeoutMinutes());
            timeoutTask = watchdog.schedule(() -> {
                if (!job.finished && !job.cancelRequested)
                {
                    record(job, "== 补元数据超时（" + minutes + " 分钟），正在强杀子进程（pid=" + pidOf(running) + "）");
                    running.destroyForcibly();
                }
            }, minutes, TimeUnit.MINUTES);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    record(job, line);
                    noteVerify(job, line);
                }
            }
            if (running.waitFor(10, TimeUnit.SECONDS))
            {
                exit = running.exitValue();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            record(job, "== 补元数据线程被中断");
        }
        catch (Exception e)
        {
            // 补元数据失败不影响「扫描成功」这个结论：文件行已经进库，缺的是四列元数据
            log.warn("扫描入库补元数据异常 job={}", job.jobId, e);
            record(job, "WARN 补元数据阶段异常（视频元数据可能仍是 NULL）：" + e);
        }
        finally
        {
            if (timeoutTask != null)
            {
                timeoutTask.cancel(false);
            }
            job.process = null;
        }
        record(job, "== 补元数据结束 exit=" + (exit == null ? "-" : exit)
                + (job.verifyMissingDur > 0 ? "（仍有 " + job.verifyMissingDur + " 条缺 dur）" : ""));
        if (job.verifyTotal < 0)
        {
            // 脚本连 VERIFY 都没打出来（读库失败 / 脚本本身坏了）：不能只说 exit!=0，
            // 必须点明后果——本根视频的元数据可能仍是 NULL（P5.96 的问题现场就是这么被静默掉的）
            record(job, "WARN 补元数据未完成：ffprobe 未生效或 enrich-meta.js 失败，"
                    + "root_id=" + job.rootId + " 的视频 dur/fps/w/h 可能仍为 NULL（详见上面的失败行）");
        }
    }

    /**
     * 解析并记录 {@code VERIFY} 行：这个库根下多少行、还剩几行 dur / fps 为 NULL
     *
     * <p>missing_dur &gt; 0 = ffprobe 没生效（配置缺失 / 路径不对 / 文件坏），必须留一条 WARN，
     * 否则「扫描成功」会把「元数据没补上」掩盖过去（P5.96 的问题现场就是这样）。</p>
     */
    private static void noteVerify(Job job, String line)
    {
        Matcher matcher = VERIFY.matcher(line);
        if (!matcher.find())
        {
            return;
        }
        job.verifyRootId = Integer.parseInt(matcher.group(1));
        job.verifyTotal = Integer.parseInt(matcher.group(2));
        job.verifyMissingDur = Integer.parseInt(matcher.group(3));
        job.verifyMissingFps = Integer.parseInt(matcher.group(4));
        if (job.verifyMissingDur > 0)
        {
            record(job, "WARN " + job.verifyMissingDur + " 条视频缺少时长元数据（ffprobe 未生效）：root_id="
                    + job.verifyRootId + " 共 " + job.verifyTotal + " 条；请检查 qtools.media.ffprobe 与补元数据输出");
        }
        else
        {
            record(job, "== 元数据校验通过：root_id=" + job.verifyRootId + " 共 " + job.verifyTotal
                    + " 条，dur 无 NULL" + (job.verifyMissingFps > 0 ? "（" + job.verifyMissingFps + " 条缺 fps）" : ""));
        }
    }

    // ------------------------------------------------------------------ 流水线阶段

    /**
     * 阶段是否「必需」（失败即整任务失败）
     *
     * <p>{@code hash} / {@code meta} 是<b>修补</b>阶段：扫描阶段已经把 content_hash 写进库，
     * 补元数据失败也只是 dur / fps 缺列——库本身可用。这两段失败只标红并可单步重试，不把整条流水线
     * 判死（否则一个历史空值就能让「入库」看起来失败）。{@code scan / thumb / sprite / index} 是必需的：
     * 少任何一段，站端看到的东西就不对。</p>
     */
    private static boolean isRequiredPhase(String key)
    {
        return PHASE_SCAN.equals(key) || PHASE_THUMB.equals(key) || PHASE_SPRITE.equals(key)
                || PHASE_INDEX.equals(key);
    }

    /** 阶段计划：{@code scan} 永远第一段，后续段按请求开关追加（顺序即执行顺序） */
    private static List<Phase> planPhases(boolean pipeline, boolean wantHash, boolean wantIndex, String derive,
            boolean withVideo)
    {
        List<Phase> phases = new ArrayList<>();
        phases.add(new Phase(PHASE_SCAN, "扫描入库"));
        if (!pipeline)
        {
            return phases;
        }
        if (wantHash)
        {
            phases.add(new Phase(PHASE_HASH, "补内容哈希"));
        }
        if (withVideo)
        {
            // scan-media.js 不跑 ffprobe（一次扫盘不该对 1 万条逐个开子进程），所以补元数据单列一段
            phases.add(new Phase(PHASE_META, "补元数据"));
        }
        if (DERIVE_THUMB.equals(derive) || DERIVE_FULL.equals(derive))
        {
            phases.add(new Phase(PHASE_THUMB, "生成封面缩略图"));
        }
        if (DERIVE_FULL.equals(derive))
        {
            phases.add(new Phase(PHASE_SPRITE, "生成精灵图预览"));
        }
        if (wantIndex)
        {
            phases.add(new Phase(PHASE_INDEX, "重建索引快照"));
        }
        return phases;
    }

    /** derive 归一：缺省 {@code thumb}（只做封面 + 图片缩略图，最省时间），非法值一律 400 */
    private static String normalizeDerive(String raw, boolean pipeline)
    {
        if (!pipeline)
        {
            return DERIVE_NONE;
        }
        String text = raw == null ? "" : raw.trim().toLowerCase();
        if (text.isEmpty())
        {
            return DERIVE_THUMB;
        }
        if (DERIVE_NONE.equals(text) || DERIVE_THUMB.equals(text) || DERIVE_FULL.equals(text))
        {
            return text;
        }
        throw MediaException.badRequest("derive 只接受 none / thumb / full（收到：" + raw + "）");
    }

    private static String phaseKeys(List<Phase> phases)
    {
        List<String> keys = new ArrayList<>();
        for (Phase phase : phases)
        {
            keys.add(phase.key);
        }
        return String.join(",", keys);
    }

    private static void beginPhase(Job job, Phase phase)
    {
        if (phase == null)
        {
            return;
        }
        synchronized (job)
        {
            phase.status = PHASE_RUNNING;
            phase.startedAt = new Date();
            phase.finishedAt = null;
            phase.message = "";
            job.currentPhase = phase.key;
        }
    }

    private static void endPhase(Job job, Phase phase, String status, String message)
    {
        if (phase == null)
        {
            return;
        }
        synchronized (job)
        {
            phase.status = status;
            phase.finishedAt = new Date();
            phase.message = message == null ? "" : message;
            job.currentPhase = null;
        }
        record(job, "== 阶段[" + phase.label + "] " + status + (message == null || message.isEmpty() ? "" : "：" + message)
                + " 耗时=" + phase.costMs() + "ms");
    }

    /** 把从 {@code fromKey} 起还没跑过的阶段一次性标成 skipped / cancelled（前序阶段失败 / 被取消时用） */
    private static void skipPhasesFrom(Job job, String fromKey, String status, String message)
    {
        boolean seen = false;
        for (Phase phase : job.phases)
        {
            if (!seen)
            {
                seen = fromKey.equals(phase.key);
                if (!seen)
                {
                    continue;
                }
            }
            if (PHASE_PENDING.equals(phase.status))
            {
                phase.status = status;
                phase.finishedAt = new Date();
                phase.message = message;
            }
        }
    }

    /** 依次跑完计划里的后续阶段；任一段失败就停（后面的标 skipped，等用户单步重试） */
    private void runFollowUps(Job job)
    {
        for (Phase phase : job.phases)
        {
            if (PHASE_SCAN.equals(phase.key) || !PHASE_PENDING.equals(phase.status))
            {
                continue;
            }
            if (job.cancelRequested)
            {
                skipPhasesFrom(job, phase.key, PHASE_CANCELLED, "任务已取消，本阶段未执行");
                return;
            }
            boolean ok = runOnePhase(job, phase);
            if (!ok)
            {
                if (isRequiredPhase(phase.key))
                {
                    skipPhasesFrom(job, nextKeyOf(job, phase.key), PHASE_SKIPPED,
                            "前序阶段「" + phase.label + "」失败，本阶段未执行（可在该阶段上点重试）");
                    return;
                }
                // 修补阶段失败不挡路：库已经可用（哈希已在扫描阶段写入 / 元数据只是缺列），继续跑后面
                record(job, "WARN 修补阶段「" + phase.label + "」失败，继续执行后续阶段（可在这段上点重试）");
            }
        }
    }

    private static String nextKeyOf(Job job, String key)
    {
        boolean seen = false;
        for (Phase phase : job.phases)
        {
            if (seen)
            {
                return phase.key;
            }
            seen = key.equals(phase.key);
        }
        return key;
    }

    /**
     * 跑一个阶段（幂等：每段的实际动作都可以重复执行）
     *
     * @return true = 成功或按设计跳过；false = 失败（阶段上已记原因，可单独重试）
     */
    private boolean runOnePhase(Job job, Phase phase)
    {
        if (PHASE_HASH.equals(phase.key))
        {
            return runMaintenancePhase(job, phase, "backfill-hash", mediaProperties.getMaintainTimeoutMinutes());
        }
        if (PHASE_THUMB.equals(phase.key))
        {
            return runMaintenancePhase(job, phase, "thumb", mediaProperties.getThumbTimeoutMinutes());
        }
        if (PHASE_SPRITE.equals(phase.key))
        {
            return runMaintenancePhase(job, phase, "sprite,preview", mediaProperties.getDeriveTimeoutMinutes());
        }
        if (PHASE_META.equals(phase.key))
        {
            return runMetaPhase(job, phase);
        }
        if (PHASE_INDEX.equals(phase.key))
        {
            return runIndexPhase(job, phase);
        }
        return true;
    }

    /**
     * 维护任务阶段：整段交给 {@link IMediaScanService}（命令白名单 / 超时 / 进度解析都在那边），
     * 这里只负责「提交 → 轮询 → 落阶段状态」
     */
    private boolean runMaintenancePhase(Job job, Phase phase, String mode, int timeoutMinutes)
    {
        beginPhase(job, phase);
        Integer exit = null;
        long childId;
        try
        {
            Map<String, Object> startView;
            synchronized (mediaScanGate.lock)
            {
                // 流水线自己的子任务要过这扇门：不加这个标记，MediaScanService 的互斥会把我们自己挡下来
                mediaScanGate.phaseEntering();
                try
                {
                    startView = mediaScanService.start(new MediaScanRequest(null, mode, false, null));
                }
                finally
                {
                    mediaScanGate.phaseEntered();
                }
            }
            childId = longOf(startView.get("jobId"), startView.get("id"));
            if (childId <= 0L)
            {
                endPhase(job, phase, PHASE_FAILED, "后端没有返回维护任务 id（mode=" + mode + "）");
                return false;
            }
            job.childJobId = String.valueOf(childId);
            record(job, "== 阶段[" + phase.label + "] 已提交维护任务 #" + childId + "（mode=" + mode + "）");
            Map<String, Object> childView = awaitChild(job, childId, phase, timeoutMinutes);
            if (childView == null)
            {
                endPhase(job, phase, PHASE_FAILED, "等待维护任务 #" + childId + " 超时（" + timeoutMinutes + " 分钟）");
                return false;
            }
            String childStatus = stringOf(childView.get("status"));
            recordChildTail(job, childView);
            if (STATUS_DONE.equals(childStatus))
            {
                exit = 0;
            }
            if (exit != null && PHASE_HASH.equals(phase.key) && isNothingToBackfill(childView))
            {
                endPhase(job, phase, PHASE_SKIPPED, "扫描阶段已写入全部 content_hash，无需补算（维护任务 #" + childId + " 空跑完成）");
                return true;
            }
            if (exit != null)
            {
                endPhase(job, phase, PHASE_DONE, describeChild(childView, childId));
                return true;
            }
            endPhase(job, phase, STATUS_CANCELLED.equals(childStatus) ? PHASE_CANCELLED : PHASE_FAILED,
                    "维护任务 #" + childId + " 结束状态 " + (childStatus.isEmpty() ? "未知" : childStatus)
                            + tailHint(childView));
            return false;
        }
        catch (MediaException e)
        {
            endPhase(job, phase, PHASE_FAILED, "提交维护任务失败：" + e.getMessage());
            return false;
        }
        catch (Exception e)
        {
            log.error("流水线阶段异常 job={} phase={}", job.jobId, phase.key, e);
            endPhase(job, phase, PHASE_FAILED, "阶段异常：" + e);
            return false;
        }
        finally
        {
            job.childJobId = null;
        }
    }

    /** 轮询维护任务直到结束（或被取消 / 超时）；返回最终视图，超时返回 null */
    private Map<String, Object> awaitChild(Job job, long childId, Phase phase, int timeoutMinutes)
    {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMinutes + 5) * 60_000L;
        boolean cancelSent = false;
        Map<String, Object> view = null;
        while (true)
        {
            try
            {
                view = mediaScanService.getJob(childId);
            }
            catch (Exception e)
            {
                record(job, "WARN 读取维护任务 #" + childId + " 状态失败：" + e.getMessage());
            }
            String status = view == null ? "" : stringOf(view.get("status"));
            if (STATUS_DONE.equals(status) || STATUS_FAILED.equals(status) || STATUS_CANCELLED.equals(status))
            {
                return view;
            }
            if (job.cancelRequested && !cancelSent)
            {
                cancelSent = true;
                record(job, "== 阶段[" + phase.label + "] 收到取消请求：取消维护任务 #" + childId);
                try
                {
                    mediaScanService.cancel(childId);
                }
                catch (Exception e)
                {
                    record(job, "WARN 取消维护任务 #" + childId + " 失败：" + e.getMessage());
                }
            }
            if (System.currentTimeMillis() > deadline)
            {
                return null;
            }
            try
            {
                Thread.sleep(PHASE_POLL_MILLIS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return view;
            }
        }
    }

    /** 维护任务日志里的「这条没事干」判据：backfill-hash 收尾行 {@code 回填 videos=0 photos=0} */
    private static boolean isNothingToBackfill(Map<String, Object> childView)
    {
        String tail = stringOf(childView.get("logTail"));
        if (tail.isEmpty())
        {
            return false;
        }
        Matcher matcher = BACKFILL.matcher(tail);
        while (matcher.find())
        {
            if ("0".equals(matcher.group(1)) && "0".equals(matcher.group(2)))
            {
                return true;
            }
        }
        return false;
    }

    /** 维护任务的收尾文案：有 derive 结果就用 ok/skip/fail，否则用 done/total */
    private static String describeChild(Map<String, Object> childView, long childId)
    {
        Object derive = childView.get("derive");
        if (derive instanceof Map)
        {
            Map<?, ?> map = (Map<?, ?>) derive;
            return "维护任务 #" + childId + " ok=" + map.get("ok") + " skip=" + map.get("skip") + " fail=" + map.get("fail");
        }
        return "维护任务 #" + childId + " 完成：done=" + childView.get("done") + "/" + childView.get("total");
    }

    private static String tailHint(Map<String, Object> childView)
    {
        List<String> lines = lastLogLines(childView.get("logTail"), 3);
        return lines.isEmpty() ? "" : ("；日志尾部：" + String.join(" ⏎ ", lines));
    }

    /** 只有最后 12 行进本任务的日志：维护任务自己有完整日志（维护面板可查），这里只要结论行 */
    private static void recordChildTail(Job job, Map<String, Object> childView)
    {
        for (String line : lastLogLines(childView.get("logTail"), 12))
        {
            record(job, "│ " + line);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> lastLogLines(Object tail, int max)
    {
        String text = stringOf(tail);
        if (text.isEmpty())
        {
            return new ArrayList<>();
        }
        List<String> all = new ArrayList<>();
        for (String line : text.split("\n"))
        {
            if (!line.trim().isEmpty())
            {
                all.add(line.trim());
            }
        }
        return all.size() <= max ? all : new ArrayList<>(all.subList(all.size() - max, all.size()));
    }

    /** 补元数据阶段：root 作用域内的 enrich-meta.js（P5.96 起就是扫描的自然延续） */
    private boolean runMetaPhase(Job job, Phase phase)
    {
        beginPhase(job, phase);
        try
        {
            enrichAfterScan(job);
        }
        catch (Exception e)
        {
            endPhase(job, phase, PHASE_FAILED, "补元数据异常：" + e);
            return false;
        }
        if (job.verifyTotal < 0)
        {
            endPhase(job, phase, PHASE_FAILED, "补元数据未完成（脚本没打出 VERIFY：ffprobe 或 enrich-meta.js 不可用）");
            return false;
        }
        String message = "root_id=" + job.verifyRootId + " 共 " + job.verifyTotal + " 条，缺 dur=" + job.verifyMissingDur
                + " 缺 fps=" + job.verifyMissingFps;
        endPhase(job, phase, PHASE_DONE, message);
        return true;
    }

    /** 重建索引快照：失效 + 立刻重算一次（站端下一次请求就是新内容，不必再等 60s TTL） */
    private boolean runIndexPhase(Job job, Phase phase)
    {
        beginPhase(job, phase);
        try
        {
            mediaIndexService.invalidateSnapshot();
            MediaIndexSnapshot snapshot = mediaIndexService.indexSnapshot(false);
            endPhase(job, phase, PHASE_DONE, "索引快照已重建：ETag=" + snapshot.getEtag()
                    + " 体积=" + snapshot.getBody().length + " 字节");
            return true;
        }
        catch (Exception e)
        {
            log.error("流水线索引阶段失败 job={}", job.jobId, e);
            endPhase(job, phase, PHASE_FAILED, "重建索引快照失败：" + e);
            return false;
        }
    }

    /**
     * 单步重试：只重跑失败（或用户点名）的那一步，成功后自动把后面还没成功的阶段接上
     *
     * <p>为什么允许「重试成功后自动续跑」：幂等 + 一次点击原则。用户点的是「把这次入库做完」，
     * 不是「把第 3 步单独做完再说」——每段都幂等，续跑不会重复写库、也不会重复解码已是最新的产物。</p>
     */
    @Override
    public Map<String, Object> retryPhase(String jobId, String phaseKey)
    {
        Job job = jobId == null ? null : jobs.get(jobId);
        if (job == null)
        {
            throw MediaException.notFound("scan job not found: " + jobId);
        }
        String key = phaseKey == null ? "" : phaseKey.trim();
        Phase phase = job.phaseOf(key);
        if (phase == null)
        {
            throw MediaException.badRequest("阶段不存在：" + key + "（本任务阶段："
                    + phaseKeys(job.phases) + "）");
        }
        if (job.dryRun)
        {
            throw MediaException.badRequest("dry-run 预演只有扫描阶段，没有可重试的后续阶段");
        }
        synchronized (mediaScanGate.lock)
        {
            if (STATUS_RUNNING.equals(job.status))
            {
                throw MediaException.conflict("任务正在运行（当前阶段 " + (job.currentPhase == null ? "-" : job.currentPhase)
                        + "），等它结束或先取消再重试");
            }
            if (mediaScanGate.isIngestRunning() || mediaScanGate.isPipelineRunning()
                    || mediaScanJobMapper.countRunning() > 0)
            {
                throw MediaException.conflict("已有扫描 / 维护任务在运行，请等待结束后再重试");
            }
            mediaScanGate.pipelineStarted(job.jobId);
            if (PHASE_SCAN.equals(key))
            {
                mediaScanGate.ingestStarted(job.jobId);
            }
        }
        synchronized (job)
        {
            job.status = STATUS_RUNNING;
            job.finished = false;
            job.finishedAt = null;
            job.reason = null;
            job.cancelRequested = false;
            job.timedOut = false;
            phase.status = PHASE_PENDING;
            phase.startedAt = null;
            phase.finishedAt = null;
            phase.message = "";
            job.currentPhase = null;
        }
        if (PHASE_SCAN.equals(key))
        {
            job.exitCode = null;
        }
        log.info("扫描入库单步重试 job={} phase={}", job.jobId, key);
        executor.submit(() -> runRetry(job, phase));
        return view(job, 0);
    }

    private void runRetry(Job job, Phase phase)
    {
        long begin = System.currentTimeMillis();
        Integer exit = job.exitCode;
        boolean ok;
        if (PHASE_SCAN.equals(phase.key))
        {
            exit = runScanPhase(job);
            ok = exit != null && exit == 0;
        }
        else
        {
            if (!PHASE_DONE.equals(phase.status) && !PHASE_PENDING.equals(phase.status))
            {
                phase.status = PHASE_PENDING;
            }
            ok = runOnePhase(job, phase);
        }
        if (ok)
        {
            // 续跑后面还没成功的阶段（跳过已 done 的：幂等 + 不重复解码）
            for (Phase next : job.phases)
            {
                if (!PHASE_PENDING.equals(next.status) && !PHASE_SKIPPED.equals(next.status)
                        && !PHASE_CANCELLED.equals(next.status))
                {
                    continue;
                }
                if (job.cancelRequested)
                {
                    break;
                }
                if (!runOnePhase(job, next))
                {
                    break;
                }
            }
        }
        finish(job, exit, begin);
    }

    // ------------------------------------------------------------------ 预演（dry-run，不写库）

    /**
     * 新增库根前的预演：{@code POST /api/media/admin/scan/preview} {@code {path}}
     *
     * <p>为什么需要「带路径的预演」而不是直接 {@code POST /scan {dryRun:true}}：那个入口要求
     * {@code rootId}（媒体根只能来自 {@code media_root.path}），而这时库根<b>还没建</b>——
     * 表单填完路径就要看到「下面到底有多少东西」，否则用户只能先建一个根、再回扫描页试、再删。
     * 这里的边界写死三条：① 只读（脚本 dry-run 不连库不写库，本方法也不写 media_root）；
     * ② 只给 {@code media:admin:edit}；③ 路径必须是绝对路径、存在、是目录、可读。</p>
     */
    @Override
    public Map<String, Object> preview(String rawPath)
    {
        String pathText = rawPath == null ? "" : rawPath.trim();
        if (pathText.isEmpty())
        {
            throw MediaException.badRequest("path 必填（服务器上的绝对路径，如 E:/视频）");
        }
        if (pathText.contains(".."))
        {
            throw MediaException.badRequest("path 不接受 .. 相对段：" + pathText);
        }
        if (!isAbsolutePath(pathText))
        {
            throw MediaException.badRequest("path 必须是绝对路径：" + pathText);
        }
        Path dir;
        try
        {
            dir = Paths.get(pathText);
        }
        catch (Exception e)
        {
            throw MediaException.badRequest("path 不合法：" + pathText + "（" + e.getMessage() + "）");
        }
        if (!Files.exists(dir))
        {
            throw MediaException.badRequest("路径不存在：" + pathText);
        }
        if (!Files.isDirectory(dir))
        {
            throw MediaException.badRequest("路径不是目录：" + pathText);
        }
        if (!Files.isReadable(dir))
        {
            throw MediaException.badRequest("路径不可读（无权限）：" + pathText);
        }
        long dryBegin = System.currentTimeMillis();
        PreviewRun run = runPreviewProcess(buildPreviewCommand(pathText));
        long dryCost = System.currentTimeMillis() - dryBegin;
        if (run.exit != 0)
        {
            throw MediaException.badRequest("预演失败（脚本退出码 " + run.exit + "）："
                    + (run.lines.isEmpty() ? "脚本没有输出" : String.join(" ⏎ ", lastOf(run.lines, 6))));
        }
        int videos = -1;
        int albums = -1;
        int photos = -1;
        for (String line : run.lines)
        {
            Matcher matcher = COUNTS.matcher(line);
            if (matcher.find())
            {
                videos = Integer.parseInt(matcher.group(1));
                albums = Integer.parseInt(matcher.group(2));
                photos = Integer.parseInt(matcher.group(3));
                break;
            }
        }
        if (videos < 0)
        {
            throw MediaException.badRequest("预演没有给出条数（脚本输出不认识）："
                    + (run.lines.isEmpty() ? "脚本没有输出" : String.join(" ⏎ ", lastOf(run.lines, 6))));
        }
        long walkFiles = 0L;
        long walkBytes = 0L;
        Map<String, Object> ignored = new LinkedHashMap<>();
        boolean truncated = false;
        String truncatedReason = "";
        try
        {
            MediaScanPreviewSupport.Walk walk = MediaScanPreviewSupport.walk(dir);
            walkFiles = walk.files;
            walkBytes = walk.bytes;
            truncated = walk.truncated;
            truncatedReason = walk.truncatedReason;
            ignored.putAll(walk.ignoredByExt);
        }
        catch (Exception e)
        {
            // 忽略类型统计失败不该让「有多少视频」这件事也拿不到：三个数字才是主体
            log.warn("预演忽略类型统计失败 path={}", pathText, e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", pathText);
        out.put("exists", true);
        out.put("readable", true);
        out.put("videos", videos);
        out.put("albums", albums);
        out.put("photos", photos);
        out.put("files", walkFiles);
        out.put("bytes", walkBytes);
        out.put("ignored", ignored);
        out.put("ignoredTotal", ignored.isEmpty() ? 0L
                : ignored.values().stream().mapToLong(value -> longOf(value, 0L)).sum());
        out.put("walkTruncated", truncated);
        out.put("walkTruncatedReason", truncatedReason);
        out.put("dryRunMs", dryCost);
        out.put("estimateSeconds", MediaScanPreviewSupport.estimateSeconds(dryCost, videos, photos, walkFiles));
        out.put("command", String.join(" ", buildPreviewCommand(pathText)));
        return out;
    }

    /** 预演命令：{@code node scanMedia.js <scanArgs 过滤后> --root=<path> --dry-run}（无 --root-id：不读库） */
    private String[] buildPreviewCommand(String pathText)
    {
        List<String> command = new ArrayList<>();
        command.add(requireFile(mediaProperties.getNodePath(), "nodePath"));
        command.add(requireFile(mediaProperties.getScanScript(), "scanScript"));
        MediaScanArgs.appendConfigured(command, mediaProperties.getScanArgs(), SCAN_FORBIDDEN_ARGS, SCAN_MANAGED_ARGS,
                SCAN_FORBIDDEN_PREFIXES);
        command.add("--root=" + pathText);
        command.add("--dry-run");
        return command.toArray(new String[0]);
    }

    /** 同步跑一次预演子进程（带超时），把输出按行收下来 */
    private PreviewRun runPreviewProcess(String[] command)
    {
        PreviewRun run = new PreviewRun();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(requireOutDir());
        builder.redirectErrorStream(true);
        applyToolEnv(builder);
        Process process = null;
        try
        {
            process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    if (run.lines.size() < 400)
                    {
                        run.lines.add(line);
                    }
                }
            }
            if (!process.waitFor(PREVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                throw new MediaException(408, "预演超时（" + PREVIEW_TIMEOUT_SECONDS + " 秒还没走完）："
                        + "目录太大或盘很慢，可先用子目录试；真实扫描同样是这个量级");
            }
            run.exit = process.exitValue();
            return run;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new MediaException(500, "预演被中断");
        }
        catch (MediaException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new MediaException(500, "预演子进程启动失败：" + e.getMessage());
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                process.destroyForcibly();
            }
        }
    }

    private static boolean isAbsolutePath(String text)
    {
        if (text.isEmpty())
        {
            return false;
        }
        char first = text.charAt(0);
        if (first == '/' || first == '\\')
        {
            return true;
        }
        return text.length() > 2 && Character.isLetter(first) && text.charAt(1) == ':'
                && (text.charAt(2) == '/' || text.charAt(2) == '\\');
    }

    private static List<String> lastOf(List<String> lines, int max)
    {
        return lines.size() <= max ? lines : new ArrayList<>(lines.subList(lines.size() - max, lines.size()));
    }

    private static long longOf(Object... values)
    {
        for (Object value : values)
        {
            if (value instanceof Number)
            {
                return ((Number) value).longValue();
            }
            if (value != null)
            {
                try
                {
                    return Long.parseLong(String.valueOf(value).trim());
                }
                catch (NumberFormatException e)
                {
                    // 下一个候选
                }
            }
        }
        return 0L;
    }

    private static String stringOf(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    /** 预演子进程的一次运行结果 */
    private static final class PreviewRun
    {
        private int exit;

        private final List<String> lines = new ArrayList<>();
    }

    private void finish(Job job, Integer exit, long begin)
    {
        String status;
        String reason = null;
        if (job.cancelRequested)
        {
            status = STATUS_CANCELLED;
            reason = "已取消（协作式：destroy 子进程）";
        }
        else if (job.timedOut)
        {
            status = STATUS_FAILED;
            reason = "超时（" + timeoutMinutes() + " 分钟），已强杀子进程";
        }
        else if (exit == null)
        {
            status = STATUS_FAILED;
            reason = "子进程未正常退出（没有拿到退出码）";
        }
        else if (exit == 0)
        {
            // 扫描成功后由「后续阶段」决定结论：必需阶段失败 → 整任务 failed；
            // 修补阶段（补哈希 / 补元数据）失败 → 任务仍是 done，但 reason 里点明原因（可单步重试）
            Phase failed = job.firstFailedPhase(true);
            Phase warned = job.firstFailedPhase(false);
            if (failed != null)
            {
                status = STATUS_FAILED;
                reason = "后续阶段「" + failed.label + "」失败：" + failed.message;
            }
            else if (warned != null)
            {
                status = STATUS_DONE;
                reason = "修补阶段「" + warned.label + "」失败（库已可用，可单步重试）：" + warned.message;
            }
            else
            {
                status = STATUS_DONE;
            }
        }
        else
        {
            status = STATUS_FAILED;
            reason = "子进程退出码 " + exit;
        }
        long cost = System.currentTimeMillis() - begin;
        int[] counters = job.snapshotCounters();
        record(job, "== 结束 status=" + status + " exit=" + (exit == null ? "-" : exit) + " videos=" + counters[C_VIDEOS]
                + " albums=" + counters[C_ALBUMS] + " photos=" + counters[C_PHOTOS] + " skipped=" + counters[C_SKIPPED]
                + " errors=" + counters[C_ERRORS] + " 耗时=" + cost + "ms"
                + (reason == null ? "" : "（" + reason + "）"));
        job.exitCode = exit;
        job.reason = reason;
        job.finishedAt = new Date();
        job.finished = true;
        job.status = status;
        job.process = null;
        mediaScanGate.ingestFinished(job.jobId);
        mediaScanGate.pipelineFinished(job.jobId);
        // 磁盘内容已经写进 media_*：快照必须失效，否则站端 60s 内看不到新内容。
        // dry-run 什么都没写，就不必让下一次请求白重算一次快照。
        if (!job.dryRun)
        {
            mediaIndexService.invalidateSnapshot();
        }
        log.info("扫描入库结束 job={} rootId={} status={} exit={} videos={} albums={} photos={} skipped={} errors={} cost={}ms",
                job.jobId, job.rootId, status, exit, counters[C_VIDEOS], counters[C_ALBUMS], counters[C_PHOTOS],
                counters[C_SKIPPED], counters[C_ERRORS], cost);
    }

    // ------------------------------------------------------------------ 日志 / 计数

    /** 收一行日志：进环形缓冲 + 解析关键行更新 counters（同一把 job 监视器，读的快照不会半截） */
    private static void record(Job job, String line)
    {
        if (line == null)
        {
            return;
        }
        synchronized (job)
        {
            job.lines.addLast(line);
            while (job.lines.size() > LOG_MAX_LINES)
            {
                job.lines.removeFirst();
            }
            job.lineCount += 1;
            if (line.startsWith("videos="))
            {
                // 只认脚本第一段的源数据条数：后面那行「条数 videos=…」是整库 COUNT(*)，
                // 多库根时它会大于本根磁盘上的条数，拿它当本次结果会直接对不上号
                Matcher counts = COUNTS.matcher(line);
                if (counts.find())
                {
                    job.counters[C_VIDEOS] = Integer.parseInt(counts.group(1));
                    job.counters[C_ALBUMS] = Integer.parseInt(counts.group(2));
                    job.counters[C_PHOTOS] = Integer.parseInt(counts.group(3));
                }
            }
            Matcher skipped = SKIPPED.matcher(line);
            if (skipped.find())
            {
                job.counters[C_SKIPPED] = Integer.parseInt(skipped.group(1));
            }
            if (line.startsWith("ERROR ") || line.startsWith("== 执行异常") || line.startsWith("== 线程被中断"))
            {
                job.counters[C_ERRORS] += 1;
            }
            if (line.startsWith("WARN "))
            {
                job.warnings += 1;
            }
        }
    }

    // ------------------------------------------------------------------ 命令 / 配置

    /**
     * 命令行：{@code node <scanScript> [scanArgs 过滤后] --root=<path> --root-id=<id> [--dry-run]
     * [--only-video|--only-photo] [--limit=n] [--purge-unclaimed]}
     *
     * <p>数据库连接（{@code --port=} / {@code --secret=} / {@code --album-mode=}）照旧来自
     * {@code qtools.media.scanArgs}：那是部署环境的事，页面只决定「扫哪个根、扫什么、扫多少」。</p>
     */
    private String[] buildCommand(String rootPath, long rootId, boolean dryRun, boolean withVideo, boolean withPhoto,
            int limit, boolean force)
    {
        List<String> command = new ArrayList<>();
        command.add(requireFile(mediaProperties.getNodePath(), "nodePath"));
        command.add(requireFile(mediaProperties.getScanScript(), "scanScript"));
        MediaScanArgs.appendConfigured(command, mediaProperties.getScanArgs(), SCAN_FORBIDDEN_ARGS, SCAN_MANAGED_ARGS,
                SCAN_FORBIDDEN_PREFIXES);
        String[] target = datasourceTarget();
        if (target != null)
        {
            command.add("--host=" + target[0]);
            command.add("--port=" + target[1]);
            command.add("--db=" + target[2]);
        }
        command.add("--root=" + rootPath);
        command.add("--root-id=" + rootId);
        if (dryRun)
        {
            command.add("--dry-run");
        }
        if (withVideo && !withPhoto)
        {
            command.add("--only-video");
        }
        if (withPhoto && !withVideo)
        {
            command.add("--only-photo");
        }
        if (limit > 0)
        {
            command.add("--limit=" + limit);
        }
        if (force)
        {
            command.add("--purge-unclaimed");
        }
        return command.toArray(new String[0]);
    }

    /**
     * 补元数据命令：{@code node enrich-meta.js [enrichArgs 过滤后] --host/--port/--db
     * --root-from-db --root-id=<n> --root=<本根>}
     *
     * <p>数据库连接与扫描阶段同源（{@link #datasourceTarget()}）：父子进程必须连同一个库，
     * 否则补的是另一个库的元数据。ffprobe 走环境变量 {@code MS_FFPROBE}（{@link #applyToolEnv}）。</p>
     */
    private String[] buildEnrichCommand(Job job)
    {
        List<String> command = new ArrayList<>();
        command.add(requireFile(mediaProperties.getNodePath(), "nodePath"));
        command.add(requireFile(mediaProperties.getEnrichScript(), "enrichScript"));
        MediaScanArgs.appendConfigured(command, mediaProperties.getEnrichArgs(), List.of(),
                List.of("--root-from-db"), ENRICH_FORBIDDEN_PREFIXES);
        String[] target = datasourceTarget();
        if (target != null)
        {
            command.add("--host=" + target[0]);
            command.add("--port=" + target[1]);
            command.add("--db=" + target[2]);
        }
        command.add("--root-from-db");
        command.add("--root-id=" + job.rootId);
        // 兜底根：本根里的行 root_id 已落这个 id，理论上用不到；留着是为了 media_root 查询失败时也能跑
        command.add("--root=" + job.rootPath);
        return command.toArray(new String[0]);
    }

    /**
     * 数据源 URL → {host, port, db}
     *
     * <p>解析不了的（非 jdbc:mysql: 形状 / 库名为空 / 含非法字符）返回 null：这时沿用 {@code scanArgs} 的连接参数
     * 并打一条 warn —— 宁可照旧跑，也不要因为解析失败把正常任务挡下来。</p>
     */
    private String[] datasourceTarget()
    {
        String url = datasourceUrl == null ? "" : datasourceUrl.trim();
        int mark = url.indexOf("//");
        if (mark < 0)
        {
            log.warn("数据源 URL 形状不认识，扫描入库沿用 scanArgs 里的连接参数: {}", url);
            return null;
        }
        String rest = url.substring(mark + 2);
        int slash = rest.indexOf('/');
        if (slash < 0)
        {
            log.warn("数据源 URL 里没有库名，扫描入库沿用 scanArgs 里的连接参数: {}", url);
            return null;
        }
        String authority = rest.substring(0, slash);
        String dbPart = rest.substring(slash + 1);
        int stop = dbPart.length();
        for (int i = 0; i < dbPart.length(); i++)
        {
            char c = dbPart.charAt(i);
            if (c == '?' || c == ';')
            {
                stop = i;
                break;
            }
        }
        String db = dbPart.substring(0, stop);
        String host = authority;
        String port = "3306";
        int colon = authority.lastIndexOf(':');
        if (colon > 0)
        {
            host = authority.substring(0, colon);
            port = authority.substring(colon + 1);
        }
        if (!TARGET_PART.matcher(host).matches() || !TARGET_PART.matcher(port).matches()
                || !TARGET_PART.matcher(db).matches())
        {
            log.warn("数据源 URL 解析出的 host/port/db 不合法（host={} port={} db={}），沿用 scanArgs", host, port, db);
            return null;
        }
        return new String[] { host, port, db };
    }

    /** 库根：必须存在且 {@code kind=root}（thumbs 根里是缩略图产物，扫进来全是无主文件） */
    private Map<String, Object> requireScanRoot(long rootId)
    {
        Map<String, Object> found = null;
        for (Map<String, Object> row : mediaMaintenanceService.listRoots())
        {
            Object id = row.get("id");
            if (id != null && String.valueOf(id).equals(String.valueOf(rootId)))
            {
                found = row;
                break;
            }
        }
        if (found == null)
        {
            throw MediaException.notFound("库根不存在: rootId=" + rootId);
        }
        Object kind = found.get("kind");
        if (kind != null && !"root".equalsIgnoreCase(String.valueOf(kind)))
        {
            throw MediaException.badRequest("rootId=" + rootId + " 的 kind=" + kind + "，只有 kind=root 的库根能扫描入库");
        }
        Object path = found.get("path");
        if (path == null || String.valueOf(path).trim().isEmpty())
        {
            throw MediaException.badRequest("rootId=" + rootId + " 没有登记路径（media_root.path 为空）");
        }
        if (Boolean.FALSE.equals(found.get("exists")))
        {
            throw MediaException.badRequest("库根路径不存在: " + path + "（磁盘上找不到这个目录）");
        }
        if (Boolean.FALSE.equals(found.get("readable")))
        {
            throw MediaException.badRequest("库根路径不可读: " + path);
        }
        return found;
    }

    /** 子进程工作目录：扫描脚本按 --out 之外不再写文件，这里只保证目录存在（脚本会读它当 cwd） */
    private File requireOutDir()
    {
        String out = mediaProperties.getScanOutPath();
        if (StringUtils.isEmpty(out))
        {
            throw new MediaException(500, "未配置 qtools.media.scanOutPath（子进程工作目录）");
        }
        File dir = new File(out);
        if (!dir.isDirectory() && !dir.mkdirs())
        {
            throw new MediaException(500, "qtools.media.scanOutPath 无法创建: " + out);
        }
        return dir;
    }

    /** node / 脚本路径：未配置或不存在都给可读错误（子进程的 CreateProcess error=2 定位不到配置项） */
    private static String requireFile(String value, String key)
    {
        if (StringUtils.isEmpty(value))
        {
            throw new MediaException(500, "未配置 qtools.media." + key);
        }
        if (!new File(value).isFile())
        {
            throw new MediaException(500, "qtools.media." + key + " 不存在: " + value);
        }
        return value;
    }

    /**
     * 子进程环境变量：{@code MS_MYSQL} + {@code MS_FFMPEG} + {@code MS_FFPROBE}
     *
     * <p>P5.96 之前这里只注入 {@code MS_MYSQL}：扫描入库的子进程拿不到 ffprobe，
     * 而 scan-media.js 本身不跑 ffprobe，于是新扫进来的视频 dur / fps / w / h 四列全 NULL。
     * 现在与维护任务共用 {@link MediaToolEnv}，扫描后的补元数据阶段才有 ffprobe 可用。</p>
     */
    private void applyToolEnv(ProcessBuilder builder)
    {
        MediaToolEnv.applyAll(builder, mediaProperties);
    }

    private int timeoutMinutes()
    {
        return Math.max(1, mediaProperties.getScanIngestTimeoutMinutes());
    }

    private static int normalizeLimit(Integer limit)
    {
        if (limit == null)
        {
            return 0;
        }
        if (limit < 0 || limit > MAX_LIMIT)
        {
            throw MediaException.badRequest("limit 必须是 0 ~ " + MAX_LIMIT + " 的整数（0 或留空 = 不限）");
        }
        return limit;
    }

    private static long pidOf(Process process)
    {
        try
        {
            return process.pid();
        }
        catch (UnsupportedOperationException e)
        {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ 视图

    private Map<String, Object> view(Job job, int tail)
    {
        List<String> lines;
        int[] counters;
        long lineCount;
        int warnings;
        synchronized (job)
        {
            lines = tail <= 0 ? new ArrayList<>() : lastLines(job.lines, tail);
            counters = job.counters.clone();
            lineCount = job.lineCount;
            warnings = job.warnings;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.jobId);
        view.put("status", job.status);
        view.put("lines", lines);
        Map<String, Object> countersView = new LinkedHashMap<>();
        countersView.put("videos", counters[C_VIDEOS]);
        countersView.put("albums", counters[C_ALBUMS]);
        countersView.put("photos", counters[C_PHOTOS]);
        countersView.put("skipped", counters[C_SKIPPED]);
        countersView.put("errors", counters[C_ERRORS]);
        view.put("counters", countersView);
        view.put("lineCount", lineCount);
        view.put("warnings", warnings);
        view.put("command", String.join(" ", job.command));
        view.put("rootId", job.rootId);
        view.put("rootName", job.rootName);
        view.put("rootPath", job.rootPath);
        view.put("dryRun", job.dryRun);
        view.put("withVideo", job.withVideo);
        view.put("withPhoto", job.withPhoto);
        view.put("limit", job.limit);
        view.put("force", job.force);
        view.put("startedAt", formatDate(job.startedAt));
        view.put("finishedAt", formatDate(job.finishedAt));
        view.put("exitCode", job.exitCode);
        view.put("reason", job.reason);
        // 补元数据校验（P5.96）：missingDur > 0 = ffprobe 没生效，前端可直接飘一条提示
        if (job.verifyTotal >= 0)
        {
            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("rootId", job.verifyRootId);
            verify.put("total", job.verifyTotal);
            verify.put("missingDur", job.verifyMissingDur);
            verify.put("missingFps", job.verifyMissingFps);
            view.put("verify", verify);
        }
        view.put("pipeline", job.pipeline);
        view.put("derive", job.derive);
        view.put("currentPhase", job.currentPhase);
        List<Map<String, Object>> phaseViews = new ArrayList<>();
        synchronized (job)
        {
            for (Phase phase : job.phases)
            {
                phaseViews.add(phase.toView());
            }
        }
        view.put("phases", phaseViews);
        if (job.finishedAt != null)
        {
            view.put("costMs", job.finishedAt.getTime() - job.startedAt.getTime());
        }
        return view;
    }

    private static List<String> lastLines(Deque<String> lines, int tail)
    {
        List<String> out = new ArrayList<>();
        int skip = Math.max(0, lines.size() - tail);
        int index = 0;
        for (String line : lines)
        {
            if (index++ >= skip)
            {
                out.add(line);
            }
        }
        return out;
    }

    private static String formatDate(Date date)
    {
        return date == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, date);
    }

    /** 一次扫描入库任务（状态全内存；读接口拿的就是它的快照） */
    private static final class Job
    {
        private final String jobId;

        private final long rootId;

        private final String rootName;

        private final String rootPath;

        private final String[] command;

        private final boolean dryRun;

        private final boolean withVideo;

        private final boolean withPhoto;

        private final int limit;

        private final boolean force;

        /** 是否跑完整条流水线（dry-run 恒 false） */
        private final boolean pipeline;

        /** 派生资源档位：none / thumb / full */
        private final String derive;

        /** 阶段计划（顺序即执行顺序；每段带自己的状态与耗时） */
        private final List<Phase> phases;

        private final Date startedAt = new Date();

        /** 环形缓冲（最多 LOG_MAX_LINES 行） */
        private final Deque<String> lines = new ArrayDeque<>();

        /** counters：videos / albums / photos / skipped / errors */
        private final int[] counters = new int[5];

        private long lineCount;

        private int warnings;

        private volatile String status = STATUS_RUNNING;

        private volatile Date finishedAt;

        private volatile Integer exitCode;

        private volatile String reason;

        private volatile Process process;

        private volatile boolean cancelRequested;

        private volatile boolean timedOut;

        private volatile boolean finished;

        /** 当前阶段的维护任务 id（补哈希 / 缩略图 / 预览）；取消时打给它 */
        private volatile String childJobId;

        /** 正在跑的阶段键（null = 没有阶段在跑） */
        private volatile String currentPhase;

        /** 补元数据阶段的校验结果（VERIFY 行；-1 = 脚本没打这行） */
        private volatile int verifyRootId = -1;

        private volatile int verifyTotal = -1;

        private volatile int verifyMissingDur = -1;

        private volatile int verifyMissingFps = -1;

        private Job(String jobId, long rootId, String rootName, String rootPath, String[] command, boolean dryRun,
                boolean withVideo, boolean withPhoto, int limit, boolean force, boolean pipeline, String derive,
                List<Phase> phases)
        {
            this.jobId = jobId;
            this.rootId = rootId;
            this.rootName = rootName;
            this.rootPath = rootPath;
            this.command = command;
            this.dryRun = dryRun;
            this.withVideo = withVideo;
            this.withPhoto = withPhoto;
            this.limit = limit;
            this.force = force;
            this.pipeline = pipeline;
            this.derive = derive;
            this.phases = phases;
        }

        /** 按阶段键取阶段；没有就 null（老任务 / dry-run 只有 scan） */
        private Phase phaseOf(String key)
        {
            for (Phase phase : phases)
            {
                if (phase.key.equals(key))
                {
                    return phase;
                }
            }
            return null;
        }

        /** 第一个失败阶段（没有则 null）；{@code requiredOnly=true} 时只看必需阶段 */
        private Phase firstFailedPhase(boolean requiredOnly)
        {
            for (Phase phase : phases)
            {
                if (PHASE_FAILED.equals(phase.status) && (!requiredOnly || isRequiredPhase(phase.key)))
                {
                    return phase;
                }
            }
            return null;
        }

        private int[] snapshotCounters()
        {
            synchronized (this)
            {
                return counters.clone();
            }
        }
    }

    /** 一个流水线阶段：状态 / 起止时间 / 一句话结论（前端就是按它画阶段条的） */
    private static final class Phase
    {
        private final String key;

        private final String label;

        private volatile String status = PHASE_PENDING;

        private volatile Date startedAt;

        private volatile Date finishedAt;

        private volatile String message = "";

        private Phase(String key, String label)
        {
            this.key = key;
            this.label = label;
        }

        private long costMs()
        {
            Date end = finishedAt == null ? new Date() : finishedAt;
            return startedAt == null ? 0L : Math.max(0L, end.getTime() - startedAt.getTime());
        }

        private Map<String, Object> toView()
        {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", key);
            view.put("label", label);
            view.put("status", status);
            view.put("startedAt", startedAt == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, startedAt));
            view.put("finishedAt", finishedAt == null ? null : DateUtils.parseDateToStr(DATE_PATTERN, finishedAt));
            view.put("costMs", startedAt == null ? null : costMs());
            view.put("message", message);
            return view;
        }
    }
}
