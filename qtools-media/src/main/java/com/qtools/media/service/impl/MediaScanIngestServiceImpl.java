package com.qtools.media.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import com.qtools.media.domain.dto.MediaScanIngestRequest;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaScanJobMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaMaintenanceService;
import com.qtools.media.service.IMediaScanIngestService;
import com.qtools.media.support.MediaScanArgs;
import com.qtools.media.support.MediaScanGate;

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

    /** 环形缓冲里的下标（counters 用一个 int[] 省一次对象分配：任务可能每秒被读几十次） */
    private static final int C_VIDEOS = 0;

    private static final int C_ALBUMS = 1;

    private static final int C_PHOTOS = 2;

    private static final int C_SKIPPED = 3;

    private static final int C_ERRORS = 4;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

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
            if (mediaScanJobMapper.countRunning() > 0)
            {
                throw MediaException.conflict("已有扫描 / 维护任务在运行（重建索引、缩略图等），请等待结束后再扫描入库");
            }
            String jobId = "scn-" + sequence.incrementAndGet();
            Job job = new Job(jobId, rootId, String.valueOf(root.get("name")), rootPath, command, dryRun,
                    withVideo, withPhoto, limit, force);
            jobs.put(jobId, job);
            mediaScanGate.ingestStarted(jobId);
            log.info("扫描入库启动 job={} rootId={} root={} dryRun={} withVideo={} withPhoto={} limit={} force={}",
                    jobId, rootId, rootPath, dryRun, withVideo, withPhoto, limit, force);
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
        Process process = null;
        ScheduledFuture<?> timeoutTask = null;
        Integer exit = null;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(job.command);
            builder.directory(requireOutDir());
            // 脚本的 WARN 全部走 stderr：合并进同一个流，日志区才看得到「已忽略参数 / 哈希失败」
            builder.redirectErrorStream(true);
            applyMysqlEnv(builder);
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
        finish(job, exit, begin);
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
            status = STATUS_DONE;
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

    /** 脚本认 MS_MYSQL（--mysql= &gt; MS_MYSQL &gt; PATH）：mysql 不在 PATH 时必须注入，否则读库失败 */
    private void applyMysqlEnv(ProcessBuilder builder)
    {
        String mysql = mediaProperties.getMysqlClient();
        if (StringUtils.isNotEmpty(mysql))
        {
            builder.environment().put("MS_MYSQL", mysql.trim());
        }
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

        private Job(String jobId, long rootId, String rootName, String rootPath, String[] command, boolean dryRun,
                boolean withVideo, boolean withPhoto, int limit, boolean force)
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
        }

        private int[] snapshotCounters()
        {
            synchronized (this)
            {
                return counters.clone();
            }
        }
    }
}
