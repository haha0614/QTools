package com.qtools.media.service.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaScanJob;
import com.qtools.media.domain.dto.MediaScanRequest;
import com.qtools.media.event.MediaScanFinishedEvent;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaScanJobMapper;
import com.qtools.media.service.IMediaIndexService;
import com.qtools.media.service.IMediaScanService;
import com.qtools.media.support.MediaScanArgs;
import com.qtools.media.support.MediaScanGate;

/**
 * 重建索引 / 维护类任务 服务实现
 *
 * <p>按 mode 选脚本，四种脚本各管一件事（命令行全部由本类按白名单拼装）：</p>
 * <ul>
 *   <li>{@code scan-media.js}：{@code mode=scan}（落库 type=full）、{@code backfill-hash}、{@code fix-poster}；</li>
 *   <li>{@code build-thumbs.js}：{@code mode=thumb} / {@code sprite} / {@code preview} / {@code sprite,preview} / {@code gc}；</li>
 *   <li>{@code enrich-meta.js}：{@code mode=enrich}（ffprobe 读 dur/w/h/vcodec 回写）；</li>
 *   <li>{@code export-bundle.js}：{@code mode=export}（产物落 {@code <exportPath>/<yyyyMMdd-HHmmss>/}）。</li>
 * </ul>
 *
 * <p>扫描脚本是 upsert 语义，因此：</p>
 * <ul>
 *   <li>绝不带 {@code --truncate}：带了对 media_* 先 DELETE，会清掉管理端的 hidden 状态，
 *       并且 media_favorite / media_recent 里的 target_id 会变成悬空引用；</li>
 *   <li>同一组开关（{@code --force} / {@code --gc} / {@code --backfill-hash} / {@code --out=} 等）
 *       只能由本类按 mode 追加，写在配置里也会被过滤，避免「按钮点的是增量、实际跑的是全量重建」；</li>
 *   <li>{@code --out} 指到 uploadPath 下，脚本产物不再写回仓库。</li>
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

    /** build_thumbs.js 收尾：DONE ok=12 skip=10347 fail=0 seconds=3 ids=id1,id2 */
    private static final Pattern THUMB_DONE = Pattern.compile("^DONE\\b.*\\bok=(\\d+)\\s+skip=(\\d+)\\s+fail=(\\d+)");

    /**
     * build_thumbs.js 逐条结果行：{@code ITEM video=<id> task=<video|sprite|pvclip> result=<ok|skip|fail> out=<相对路径> seconds=<n>}
     *
     * <p>只解析「结果」这一段：out 之后的 reason= 可有可无，所以整行用 find() 而不是 matches()。</p>
     */
    private static final Pattern THUMB_ITEM = Pattern.compile(
            "^ITEM\\s+video=(\\S+)\\s+task=(\\S+)\\s+result=(\\S+)\\s+out=(\\S+)");

    /** 任务结束的回写行：RESULT ok=3 skip=1 fail=0 ids=id1,id2 [failIds=id3] */
    private static final Pattern THUMB_RESULT = Pattern.compile(
            "^RESULT\\s+ok=(\\d+)\\s+skip=(\\d+)\\s+fail=(\\d+)\\s+ids=(\\S+)");

    /** videoIds 上限：与脚本 --video= 的命令行长度上限对齐（管理端列表一页最大 200 条） */
    private static final int MAX_VIDEO_IDS = 200;

    /** videoId 形状（库里是 varchar(16)）：只收字母数字与 _-，绝不让任意文本进命令行 */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 允许的 mode 原子值 */
    private static final List<String> MODES = List.of("scan", "thumb", "sprite", "preview", "gc",
            "enrich", "backfill-hash", "fix-poster", "export");

    /** 只能单独使用的 mode（sprite / preview 允许组合成 sprite,preview） */
    private static final List<String> SINGLE_MODES = List.of("scan", "thumb", "gc",
            "enrich", "backfill-hash", "fix-poster", "export");

    /** limit 上限：176 个视频的真实规模下，超过这个数只可能是手滑 */
    private static final int MAX_LIMIT = 10000;

    /** 脚本种类：决定用哪个 node 脚本、解析哪种输出行、注入哪些环境变量 */
    private static final String SCRIPT_SCAN = "scan";

    private static final String SCRIPT_THUMB = "thumb";

    private static final String SCRIPT_ENRICH = "enrich";

    private static final String SCRIPT_EXPORT = "export";

    /** 落库 {media_scan_job.type} 的值：scan 沿用旧值 full，其余与 mode 同名 */
    private static final String TYPE_SCAN = "full";

    private static final String TYPE_THUMB = "thumb";

    private static final String TYPE_GC = "gc";

    private static final String TYPE_ENRICH = "enrich";

    private static final String TYPE_BACKFILL = "backfill-hash";

    private static final String TYPE_FIX_POSTER = "fix-poster";

    private static final String TYPE_EXPORT = "export";

    /** 缩略图脚本的受管开关：一律由 buildThumbCommand 按 mode 追加，配置里写了也过滤 */
    private static final List<String> THUMB_MANAGED_ARGS =
            List.of("--force", "--no-fresh", "--sprite", "--preview", "--gc", "--yes", "-y");

    /** 扫描脚本的禁用参数：--truncate 会清掉 hidden 与收藏引用 */
    private static final List<String> SCAN_FORBIDDEN_ARGS = List.of("--truncate");

    /** 扫描脚本的受管开关：由 mode 决定，配置里写了也过滤（否则 mode=scan 会被配置改成回填） */
    private static final List<String> SCAN_MANAGED_ARGS = List.of("--backfill-hash", "--fix-poster-path");

    /** 导出目录名（yyyyMMdd-HHmmss）：既是产物目录名，也是下载接口唯一接受的 dir 形状 */
    private static final DateTimeFormatter EXPORT_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** enrich-meta.js 收尾：enrich ok=12 skip=3 fail=0 seconds=8 */
    private static final Pattern ENRICH_DONE = Pattern.compile("^enrich\\s+ok=(\\d+)\\s+skip=(\\d+)\\s+fail=(\\d+)");

    /** exclude 的长度上限与禁用字符：只允许目录名文本，逗号分隔多个 */
    private static final int MAX_EXCLUDE_CHARS = 200;

    private static final String EXCLUDE_FORBIDDEN_CHARS = " \t\r\n\"'`;$&|<>()\\*?!~{}[]=";

    @Autowired
    private MediaScanJobMapper mediaScanJobMapper;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private IMediaIndexService mediaIndexService;

    /** 任务结束事件：文件监听（默认不注册）靠它补跑攒下的 pending，扫描服务不反向依赖监听器 */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** 同一时刻只有一个扫描线程，天然串行 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-scan");
        thread.setDaemon(true);
        return thread;
    });

    /** 与扫描入库（P5.91）共用的唯一互斥点：两边都起 node 子进程写同一批 media_* 表 */
    @Autowired
    private MediaScanGate mediaScanGate;

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
        return start(new MediaScanRequest(type, null, false, null));
    }

    /**
     * 触发一次扫描 / 维护任务（异步，同一时刻只允许 1 个）
     *
     * <p>mode 与可选参数先在这里全量校验（不合法一律 400），再按 mode 建 job：配置缺失 / 参数非法
     * 都发生在写 {@code media_scan_job} 之前，不会留下一条 running 的假任务。</p>
     */
    @Override
    public Map<String, Object> start(MediaScanRequest request)
    {
        MediaScanRequest req = request == null ? new MediaScanRequest(null, null, false, null) : request;
        String jobType = normalizeMode(req.getType(), req.getMode());
        if (TYPE_GC.equals(jobType) && !req.isConfirm())
        {
            // 派生文件 GC 是真删：不能只靠前端弹窗，后端没有 confirm=true 一律拒绝
            throw MediaException.badRequest("mode=gc 需要显式 confirm=true（会真删 v/ s/ pv2/ 下的孤儿派生文件）");
        }
        normalizeModeOptions(jobType, req);
        req.setLimit(normalizeLimit(jobType, req.getLimit()));
        // 配置缺失必须在建 job 之前就报可读错误：否则子进程只会给 CreateProcess error=2 / NPE
        String scriptKind = scriptKindOf(jobType);
        requireConfigured(mediaProperties.getNodePath(), "nodePath");
        requireConfigured(mediaProperties.getScanOutPath(), "scanOutPath");
        requireConfigured(scriptOf(scriptKind), scriptKeyOf(scriptKind));
        if (SCRIPT_EXPORT.equals(scriptKind))
        {
            // 导出目录由后端生成：前端只能触发，不能指定写到哪儿（下载接口也只认这个形状）
            req.setExportDir(exportDirOf());
        }
        synchronized (mediaScanGate.lock)
        {
            if (mediaScanGate.isIngestRunning())
            {
                throw MediaException.conflict("已有扫描入库任务在运行（" + mediaScanGate.getIngestJobId()
                        + "），请等待结束或先取消");
            }
            if (runningJobId.get() != 0L || mediaScanJobMapper.countRunning() > 0)
            {
                throw MediaException.conflict("已有扫描任务在运行，请等待结束后再触发");
            }
            MediaScanJob job = new MediaScanJob();
            job.setType(jobType);
            job.setStatus(STATUS_RUNNING);
            mediaScanJobMapper.insertJob(job);
            runningJobId.set(job.getId());
            MediaScanRequest snapshot = req.copy();
            log.info("管理端触发扫描任务 job={} mode={} limit={} albumMode={} exclude={} noFresh={} force={} videoIds={} exportDir={}",
                    job.getId(), jobType, snapshot.getLimit(), snapshot.getAlbumMode(), snapshot.getExclude(),
                    snapshot.isNoFresh(), snapshot.isForce(), snapshot.getVideoIds() == null ? 0 : snapshot.getVideoIds().size(),
                    snapshot.getExportDir());
            executor.submit(() -> runJob(job.getId(), snapshot));
            Map<String, Object> view = toView(mediaScanJobMapper.selectJobById(job.getId()));
            if (snapshot.getExportDir() != null)
            {
                // 导出目录在执行前就已确定：前端拿着 jobId 就能轮询文件是否落地
                view.put("exportDir", snapshot.getExportDir());
            }
            return view;
        }
    }

    // ------------------------------------------------------------------ mode / 参数校验

    /**
     * mode 可选参数的取值范围校验，并把规范化后的值写回请求对象
     *
     * <p>可选参数只有四个（albumMode / exclude / noFresh / force），全部由后端按 mode 从白名单拼进命令：
     * 前端无法传任意子进程参数。越界一律 400，不静默忽略——静默忽略会让「点了按钮但没生效」无从排查。</p>
     */
    private static void normalizeModeOptions(String jobType, MediaScanRequest req)
    {
        String albumMode = trimToNull(req.getAlbumMode());
        if (albumMode != null)
        {
            if (!TYPE_SCAN.equals(jobType))
            {
                throw MediaException.badRequest("albumMode 只对 mode=scan 生效（当前 mode=" + displayModeOf(jobType) + "）");
            }
            String normalized = albumMode.toLowerCase(Locale.ROOT);
            if (!"dir".equals(normalized) && !"prefix".equals(normalized))
            {
                throw MediaException.badRequest("albumMode 只接受 dir 或 prefix（收到：" + albumMode + "）");
            }
            req.setAlbumMode(normalized);
        }
        String exclude = trimToNull(req.getExclude());
        if (exclude != null)
        {
            if (!TYPE_SCAN.equals(jobType))
            {
                throw MediaException.badRequest("exclude 只对 mode=scan 生效（当前 mode=" + displayModeOf(jobType) + "）");
            }
            validateExclude(exclude);
            req.setExclude(exclude);
        }
        if (req.isForce() && !isGenerateMode(jobType))
        {
            // force 是「忽略新鲜度、全部重建」：只有逐条产出派生文件的三种 mode 才有意义，
            // 其余 mode 没有可重建的产物，静默忽略会让「点了按钮但没生效」无从排查，一律 400
            throw MediaException.badRequest("force 只对 mode=thumb / sprite / preview 生效（当前 mode="
                    + displayModeOf(jobType) + "，该 mode 没有可重建的派生产物，请去掉 force）");
        }
        if (req.isNoFresh() && !isGenerateMode(jobType))
        {
            throw MediaException.badRequest("noFresh 只对 mode=thumb / sprite / preview 生效（当前 mode="
                    + displayModeOf(jobType) + "）");
        }
        if (req.isNoFresh() && req.isForce())
        {
            throw MediaException.badRequest("noFresh 与 force 互相矛盾：force 要重建全部，noFresh 是产物存在即跳过");
        }
        req.setVideoIds(normalizeVideoIds(jobType, req.getVideoIds()));
    }

    /**
     * videoIds 规范化：去空白 / 去重（保序）/ 校验形状与数量
     *
     * <p>ids 最终拼进 {@code --video=a,b}，所以要挡住能破坏命令行的字符；<b>不</b>校验 id 是否真的存在
     * ——脚本对查不到的 id 只 WARN 并计入 skipped，这样前端拿一页过期的列表点「生成」也不会整个 400。</p>
     *
     * @return 规范化后的 id 列表；null / 空表返回空表（= 不筛选，整库跑）
     */
    private static List<String> normalizeVideoIds(String jobType, List<String> raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return List.of();
        }
        if (!isGenerateMode(jobType))
        {
            throw MediaException.badRequest("videoIds 只对 mode=thumb / sprite / preview 生效（当前 mode="
                    + displayModeOf(jobType) + "）");
        }
        List<String> ids = new ArrayList<>();
        for (String item : raw)
        {
            String id = item == null ? "" : item.trim();
            if (id.isEmpty() || ids.contains(id))
            {
                continue;
            }
            if (!VIDEO_ID.matcher(id).matches())
            {
                throw MediaException.badRequest("videoIds 含非法 id：" + id + "（只允许字母数字与 _-，最长 32 字符）");
            }
            ids.add(id);
        }
        if (ids.size() > MAX_VIDEO_IDS)
        {
            throw MediaException.badRequest("videoIds 最多 " + MAX_VIDEO_IDS + " 个（收到 " + ids.size() + "）");
        }
        return ids;
    }

    /** exclude 只接受目录名文本：出现空格 / 引号 / 分号等会破坏命令行的字符一律 400 */
    private static void validateExclude(String exclude)
    {
        if (exclude.length() > MAX_EXCLUDE_CHARS)
        {
            throw MediaException.badRequest("exclude 过长（最多 " + MAX_EXCLUDE_CHARS + " 字符）");
        }
        for (int i = 0; i < exclude.length(); i++)
        {
            char c = exclude.charAt(i);
            if (EXCLUDE_FORBIDDEN_CHARS.indexOf(c) >= 0)
            {
                throw MediaException.badRequest("exclude 含非法字符 '" + c + "'（只允许目录名文本，逗号分隔多个）");
            }
        }
    }

    /** 逐条生成类 mode：thumb / sprite / preview / sprite,preview（gc 也算缩略图脚本，但它不是逐条生成） */
    private static boolean isGenerateMode(String jobType)
    {
        return SCRIPT_THUMB.equals(scriptKindOf(jobType)) && !TYPE_GC.equals(jobType);
    }

    /** 脚本种类 → 配置里的脚本路径（enrich 用 enrichScript，export 用 exportScript，其余按扫盘/缩略图分） */
    private String scriptOf(String scriptKind)
    {
        switch (scriptKind)
        {
            case SCRIPT_ENRICH:
                return mediaProperties.getEnrichScript();
            case SCRIPT_EXPORT:
                return mediaProperties.getExportScript();
            case SCRIPT_THUMB:
                return mediaProperties.getThumbScript();
            default:
                return mediaProperties.getScanScript();
        }
    }

    /** 脚本种类 → 配置项名（错误信息里要写出到底缺哪一项） */
    private static String scriptKeyOf(String scriptKind)
    {
        switch (scriptKind)
        {
            case SCRIPT_ENRICH:
                return "enrichScript";
            case SCRIPT_EXPORT:
                return "exportScript";
            case SCRIPT_THUMB:
                return "thumbScript";
            default:
                return "scanScript";
        }
    }

    /** 导出目录：{@code <exportPath>/<yyyyMMdd-HHmmss>}，目录名一律由后端生成 */
    private String exportDirOf()
    {
        String base = mediaProperties.getExportPath();
        requireConfigured(base, "exportPath");
        String dir = base.replace('\\', '/');
        while (dir.endsWith("/"))
        {
            dir = dir.substring(0, dir.length() - 1);
        }
        return dir + "/" + LocalDateTime.now().format(EXPORT_DIR_FORMAT);
    }

    /** 日志 / 错误信息里的 mode 名：落库值 full 对用户仍叫 scan */
    private static String displayModeOf(String jobType)
    {
        return TYPE_SCAN.equals(jobType) ? "scan" : jobType;
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
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

    private void runJob(Long jobId, MediaScanRequest req)
    {
        MediaScanJob job = mediaScanJobMapper.selectJobById(jobId);
        // type 落的就是 normalizeMode 的归一化结果（scan → full）；脚本种类由 scriptKindOf 推导：
        // full / backfill-hash / fix-poster 走 scan-media.js，thumb 系走 build-thumbs.js，
        // enrich 走 enrich-meta.js，export 走 export-bundle.js
        String jobType = job == null || StringUtils.isEmpty(job.getType()) ? TYPE_SCAN : job.getType();
        String scriptKind = scriptKindOf(jobType);
        long begin = System.currentTimeMillis();
        Deque<String> tail = new ArrayDeque<>();
        // 来源行必须是日志首行：手工触发没有这一行，自动扫描一眼可辨（AUTO 触发：<路径> 变化 N 个事件）
        if (req != null && StringUtils.isNotEmpty(req.getOrigin()))
        {
            appendLine(tail, req.getOrigin() + " 触发："
                    + (req.getTriggerNote() == null ? "" : req.getTriggerNote()));
        }
        // counters[0]=total, counters[1]=done（full 取脚本回读条数；thumb / enrich 取 START/PROGRESS/收尾行）
        int[] counters = new int[2];
        DeriveTally tally = new DeriveTally();
        int exit = -1;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(buildCommand(jobType, req));
            // 子进程工作目录必须先存在（脚本会把 import.sql / report.md 之类写进去）
            String outPath = mediaProperties.getScanOutPath();
            requireConfigured(outPath, "scanOutPath");
            File outDir = new File(outPath);
            Files.createDirectories(outDir.toPath());
            builder.directory(outDir);
            builder.redirectErrorStream(true);
            // MS_FFMPEG / MS_FFPROBE：配了才注入（留空 = 脚本自己从 PATH 找）
            applyToolEnv(builder);
            // 每个脚本都可能读库（scan / build-thumbs / enrich-meta / export-bundle），mysql 不在 PATH 时必须靠 MS_MYSQL
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
                    if (!isProgressLine(scriptKind, line) && !isItemLine(scriptKind, line))
                    {
                        appendLine(tail, line);
                    }
                    parseLine(scriptKind, line, counters);
                    if (SCRIPT_THUMB.equals(scriptKind))
                    {
                        // ITEM 行逐条累积，收尾时折成一行 RESULT 回写：200 条选片会有 600 行 ITEM，
                        // 全塞进 200 行的日志尾部会把 START/TASK/DONE 挤出去
                        tally.accept(line);
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

        if (SCRIPT_THUMB.equals(scriptKind) && tally.seen())
        {
            // 逐条结果的汇总行：前端从 job.logTail / view.derive 读「哪几条生成了、哪几条失败」
            appendLine(tail, tally.resultLine());
        }

        if (SCRIPT_EXPORT.equals(scriptKind))
        {
            // 导出产物由脚本写进 exportDir：这里把目录 / 文件清单 / 总字节落进 job 日志，便于肉眼与机器都读
            int[] exported = appendExportSummary(tail, req == null ? null : req.getExportDir(), exit == 0);
            if (exit == 0)
            {
                counters[0] = exported[0];
                counters[1] = exported[0];
            }
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
        // 必须在 runningJobId 归零之后发：监听器收到事件后立刻补跑 pending，早发会撞上自己那条 running 行
        try
        {
            eventPublisher.publishEvent(new MediaScanFinishedEvent(jobId, jobType, status));
        }
        catch (Exception e)
        {
            log.warn("扫描结束事件发布失败 job={}", jobId, e);
        }
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

    /** PROGRESS 行只进 total/done 两列，不进日志尾部（1 万张缩略图会产生 400+ 行） */
    private static boolean isProgressLine(String scriptKind, String line)
    {
        return line.startsWith("PROGRESS ")
                && (SCRIPT_THUMB.equals(scriptKind) || SCRIPT_ENRICH.equals(scriptKind));
    }

    /**
     * ITEM 行不进日志尾部：一次勾选 200 条视频就是 600 行，会把 START / TASK / FAIL 全挤出去。
     * 它们的信息在收尾时折成一行 {@code RESULT ok= skip= fail= ids=}（见 DeriveTally）。
     */
    private static boolean isItemLine(String scriptKind, String line)
    {
        return SCRIPT_THUMB.equals(scriptKind) && line.startsWith("ITEM ");
    }

    /**
     * 逐条 ITEM 行的汇总（「哪几条生成了」的直接来源）
     *
     * <p>计数按<b>产物项</b>算（一条视频的 video / sprite / pvclip 各算一项），与脚本 DONE 行同口径；
     * ids / failIds / skipIds 按<b>视频</b>去重，前端拿它就能标出「已生成 / 失败 / 跳过」的片子。</p>
     */
    private static final class DeriveTally
    {
        private final Set<String> ids = new LinkedHashSet<>();

        private final Set<String> failIds = new LinkedHashSet<>();

        private final Set<String> skipIds = new LinkedHashSet<>();

        private int ok;

        private int skip;

        private int fail;

        private void accept(String line)
        {
            Matcher matcher = THUMB_ITEM.matcher(line);
            if (!matcher.find())
            {
                return;
            }
            String videoId = matcher.group(1);
            String result = matcher.group(3);
            ids.add(videoId);
            if ("ok".equals(result))
            {
                ok += 1;
            }
            else if ("skip".equals(result))
            {
                skip += 1;
                skipIds.add(videoId);
            }
            else
            {
                fail += 1;
                failIds.add(videoId);
            }
        }

        private boolean seen()
        {
            return !ids.isEmpty();
        }

        private String resultLine()
        {
            StringBuilder sb = new StringBuilder("RESULT ok=").append(ok).append(" skip=").append(skip)
                    .append(" fail=").append(fail).append(" ids=").append(joinCapped(ids));
            if (!failIds.isEmpty())
            {
                sb.append(" failIds=").append(joinCapped(failIds));
            }
            if (!skipIds.isEmpty())
            {
                sb.append(" skipIds=").append(joinCapped(skipIds));
            }
            if (ids.size() > MAX_VIDEO_IDS)
            {
                sb.append(" truncated=1");
            }
            return sb.toString();
        }
    }

    /** 逗号拼接并截断到 MAX_VIDEO_IDS 个（超出部分丢掉，由调用方标 truncated=1） */
    private static String joinCapped(Set<String> ids)
    {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String id : ids)
        {
            if (count >= MAX_VIDEO_IDS)
            {
                break;
            }
            if (count > 0)
            {
                sb.append(',');
            }
            sb.append(id);
            count += 1;
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 按脚本种类选解析器：thumb 系认 START/PROGRESS/DONE，enrich 认 START 与 enrich ok= 收尾行，其余走扫盘回读行 */
    private static void parseLine(String scriptKind, String line, int[] counters)
    {
        if (SCRIPT_THUMB.equals(scriptKind))
        {
            parseThumbLine(line, counters);
        }
        else if (SCRIPT_ENRICH.equals(scriptKind))
        {
            parseEnrichLine(line, counters);
        }
        else
        {
            parseFullLine(line, counters);
        }
    }

    /** enrich：total 取 START 行（全部行数），done 取 {@code enrich ok= skip= fail=} 三者之和 */
    private static void parseEnrichLine(String line, int[] counters)
    {
        Matcher start = THUMB_START.matcher(line);
        if (start.find())
        {
            counters[0] = Integer.parseInt(start.group(1));
            return;
        }
        Matcher done = ENRICH_DONE.matcher(line);
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

    // ------------------------------------------------------------------ 命令拼装（白名单）

    /**
     * 按 mode 拼出子进程命令行：{@code node <脚本> [配置里的附加参数] [后端按 mode / 请求追加的开关]}
     *
     * <p>任何来自配置或请求的附加参数都要过一层过滤：受管开关（{@code --force} / {@code --gc} /
     * {@code --backfill-hash} 之类）只能由本类按 mode 追加，配置里写了也丢掉——否则「按钮点的是增量」
     * 与「实际跑的是全量重建」会静默分叉。</p>
     */
    private String[] buildCommand(String jobType, MediaScanRequest req)
    {
        switch (scriptKindOf(jobType))
        {
            case SCRIPT_ENRICH:
                return buildEnrichCommand(req);
            case SCRIPT_EXPORT:
                return buildExportCommand(req);
            case SCRIPT_SCAN:
                return buildScanCommand(jobType, req);
            default:
                return buildThumbCommand(jobType, req);
        }
    }

    /**
     * 扫描命令：{@code node scan-media.js --out=<scanOutPath> <scanArgs> [--backfill-hash | --fix-poster-path]
     * [--album-mode=dir|prefix] [--exclude=<dir,dir>]}
     *
     * <p>{@code --out=} 由本方法追加；{@code --root=} / {@code --port=} / {@code --secret=} 属于部署环境，
     * 只能写在 {@code scanArgs} 里（见 application.yml 注释）。</p>
     */
    private String[] buildScanCommand(String jobType, MediaScanRequest req)
    {
        List<String> command = new ArrayList<>();
        command.add(requireNode());
        command.add(requireScript(mediaProperties.getScanScript(), "scanScript"));
        command.add("--out=" + mediaProperties.getScanOutPath());
        List<String> forbiddenPrefixes = new ArrayList<>();
        if (req != null && req.getAlbumMode() != null)
        {
            // 指定了 albumMode 就整体替换配置里的取值：同一个参数出现两次，脚本只认先到的那次
            forbiddenPrefixes.add("--album-mode=");
        }
        if (req != null && req.getExclude() != null)
        {
            forbiddenPrefixes.add("--exclude=");
        }
        MediaScanArgs.appendConfigured(command, mediaProperties.getScanArgs(), SCAN_FORBIDDEN_ARGS, SCAN_MANAGED_ARGS,
                forbiddenPrefixes);
        if (TYPE_BACKFILL.equals(jobType))
        {
            command.add("--backfill-hash");
        }
        if (TYPE_FIX_POSTER.equals(jobType))
        {
            command.add("--fix-poster-path");
        }
        if (req != null && req.getAlbumMode() != null)
        {
            command.add("--album-mode=" + req.getAlbumMode());
        }
        if (req != null && req.getExclude() != null)
        {
            command.add("--exclude=" + req.getExclude());
        }
        return command.toArray(new String[0]);
    }

    /**
     * 缩略图 / 生成类命令：{@code node build-thumbs.js <thumbArgs> [--no-fresh] [--force]
     * [--sprite] [--preview] [--gc --yes] [--limit=n]}
     *
     * <p>脚本自带增量判定（按源文件 mtime 跳过已存在的产物），默认参数就是增量模式；
     * {@code --no-fresh}（退回「存在即跳过」的旧语义）与 {@code --force}（全部重建）只能由请求
     * 显式打开，且互斥。</p>
     *
     * <p>{@code --force} 对 thumb / sprite / preview 三档同时生效（组合 mode {@code sprite,preview}
     * 也是同一个开关）：换过取帧算法后要重生成旧片段，只能靠它越过 mtime 判定。{@code --limit=n}
     * 同样三档通用，按脚本 planTasks 的顺序（每条视频先 videos 再 sprites / pvclips）取前 n 条。</p>
     */
    private String[] buildThumbCommand(String jobType, MediaScanRequest req)
    {
        String script = requireScript(mediaProperties.getThumbScript(), "thumbScript");
        List<String> command = new ArrayList<>();
        command.add(requireNode());
        command.add(script);
        // --video= / --ids-file= 属于「本次请求勾选了哪几条」：配置里写了也丢掉，避免按钮与配置静默分叉
        MediaScanArgs.appendConfigured(command, mediaProperties.getThumbArgs(), THUMB_MANAGED_ARGS, List.of(),
                List.of("--video=", "--ids-file="));
        // jobType 是 normalizeMode 归一化后的逗号拼接值：sprite,preview 两个开关都会带上
        if (jobType.contains("sprite"))
        {
            command.add("--sprite");
        }
        if (jobType.contains("preview"))
        {
            command.add("--preview");
        }
        if (TYPE_GC.equals(jobType))
        {
            // --yes 由后端追加：缺了它脚本只干跑统计，不会真删
            command.add("--gc");
            command.add("--yes");
        }
        if (req != null && req.isNoFresh())
        {
            command.add("--no-fresh");
        }
        if (req != null && req.isForce() && isGenerateMode(jobType))
        {
            // 三档共用一个开关：thumb / sprite / preview（含 sprite,preview 组合）都按请求追加
            // --force，否则脚本的新鲜度判定（产物 mtime >= 源 mtime）会把换过取帧算法的旧片段
            // 判成「已是最新」全部跳过；gc 没有产物可重建，校验层已先 400，这里再兜一层
            command.add("--force");
        }
        Integer limit = req == null ? null : req.getLimit();
        if (limit != null && limit > 0)
        {
            command.add("--limit=" + limit);
        }
        if (req != null && req.getVideoIds() != null && !req.getVideoIds().isEmpty())
        {
            // 勾选生成：脚本只处理这些 videoId 的三档派生产物，图片缩略图一概不碰
            command.add("--video=" + String.join(",", req.getVideoIds()));
        }
        return command.toArray(new String[0]);
    }

    /**
     * 元数据补齐命令：{@code node enrich-meta.js <enrichArgs> [--limit=n]}
     *
     * <p>ffprobe 与 mysql 的路径走环境变量（{@code MS_FFPROBE} / {@code MS_MYSQL}），参数里不必写；
     * 请求侧只开放 {@code --limit=}（调试用），{@code --dry-run} / {@code --force} 仍属于配置项的事。</p>
     */
    private String[] buildEnrichCommand(MediaScanRequest req)
    {
        List<String> command = new ArrayList<>();
        command.add(requireNode());
        command.add(requireScript(mediaProperties.getEnrichScript(), "enrichScript"));
        Integer limit = req == null ? null : req.getLimit();
        MediaScanArgs.appendConfigured(command, mediaProperties.getEnrichArgs(), List.of(), List.of(),
                limit != null && limit > 0 ? List.of("--limit=") : List.of());
        if (limit != null && limit > 0)
        {
            command.add("--limit=" + limit);
        }
        return command.toArray(new String[0]);
    }

    /**
     * 便携包导出命令：{@code node export-bundle.js <exportArgs> --out=<exportDir>}
     *
     * <p>{@code --out=} 由后端生成（{@code <exportPath>/<yyyyMMdd-HHmmss>}），配置里的同名参数被过滤：
     * 导出目录是「下载接口唯一允许读的根」的前提，不能让别处指定。</p>
     */
    private String[] buildExportCommand(MediaScanRequest req)
    {
        List<String> command = new ArrayList<>();
        command.add(requireNode());
        command.add(requireScript(mediaProperties.getExportScript(), "exportScript"));
        MediaScanArgs.appendConfigured(command, mediaProperties.getExportArgs(), List.of(), List.of(), List.of("--out="));
        String exportDir = req == null ? null : req.getExportDir();
        requireConfigured(exportDir, "exportPath");
        command.add("--out=" + exportDir);
        return command.toArray(new String[0]);
    }

    /**
     * 导出产物清单 → job 日志行：{@code EXPORT ok dir=<绝对路径> files=<n> bytes=<n> names=<a,b,c>}
     *
     * @return {@code [files]}：目录下的常规文件数（thumbs 子目录不计入，单独看清单即可）
     */
    private static int[] appendExportSummary(Deque<String> tail, String exportDir, boolean ok)
    {
        List<String> names = new ArrayList<>();
        long bytes = 0L;
        File dir = StringUtils.isEmpty(exportDir) ? null : new File(exportDir);
        if (dir != null && dir.isDirectory())
        {
            File[] files = dir.listFiles(File::isFile);
            if (files != null)
            {
                for (File file : files)
                {
                    names.add(file.getName());
                    bytes += file.length();
                }
            }
            names.sort(null);
        }
        String dirText = dir == null ? "(未设置导出目录)" : dir.getAbsolutePath().replace('\\', '/');
        appendLine(tail, (ok ? "EXPORT ok" : "EXPORT fail") + " dir=" + dirText + " files=" + names.size()
                + " bytes=" + bytes + (names.isEmpty() ? "" : " names=" + String.join(",", names)));
        return new int[] { names.size() };
    }

    /**
     * mode 归一化：{@code scan | thumb | sprite | preview | gc | sprite,preview | enrich | backfill-hash |
     * fix-poster | export}
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
        for (String single : List.of("enrich", "backfill-hash", "fix-poster", "export"))
        {
            if (tokens.contains(single))
            {
                // 维护类 mode 参数固定、跑一次就完：它们不在 SINGLE_MODES 之外还允许组合
                return single;
            }
        }
        if (tokens.contains("scan"))
        {
            return TYPE_SCAN;
        }
        if (tokens.contains("thumb"))
        {
            return TYPE_THUMB;
        }
        if (tokens.contains("gc"))
        {
            return TYPE_GC;
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

    /** mode（落库值）→ 脚本种类：决定新进程用哪个脚本、解析哪种输出行、走哪档超时 */
    private static String scriptKindOf(String jobType)
    {
        if (TYPE_ENRICH.equals(jobType))
        {
            return SCRIPT_ENRICH;
        }
        if (TYPE_EXPORT.equals(jobType))
        {
            return SCRIPT_EXPORT;
        }
        if (TYPE_SCAN.equals(jobType) || TYPE_BACKFILL.equals(jobType) || TYPE_FIX_POSTER.equals(jobType))
        {
            return SCRIPT_SCAN;
        }
        return SCRIPT_THUMB;
    }

    /**
     * limit 只对逐条处理的 mode 生效（thumb / sprite / preview / enrich）
     *
     * <p>scan / gc / backfill-hash / fix-poster / export 传了直接 400：这些 mode 不支持「只跑前 n 条」，
     * 静默忽略会让调用方误以为已经「试跑过」。</p>
     */
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
        boolean limitedMode = isGenerateMode(jobType) || TYPE_ENRICH.equals(jobType);
        if (limit > 0 && !limitedMode)
        {
            throw MediaException.badRequest("mode=" + displayModeOf(jobType) + " 不支持 limit");
        }
        return limit;
    }

    /**
     * 超时预算（分钟）
     *
     * <table border="1">
     *   <caption>mode → 超时配置项</caption>
     *   <tr><th>mode</th><th>配置项</th><th>默认</th></tr>
     *   <tr><td>scan</td><td>scanTimeoutMinutes</td><td>10</td></tr>
     *   <tr><td>thumb</td><td>thumbTimeoutMinutes</td><td>30</td></tr>
     *   <tr><td>sprite / preview / sprite,preview / gc</td><td>deriveTimeoutMinutes</td><td>720</td></tr>
     *   <tr><td>enrich</td><td>enrichTimeoutMinutes</td><td>120</td></tr>
     *   <tr><td>backfill-hash / fix-poster</td><td>maintainTimeoutMinutes</td><td>120</td></tr>
     *   <tr><td>export</td><td>exportTimeoutMinutes</td><td>60</td></tr>
     * </table>
     */
    private int timeoutMinutesOf(String jobType)
    {
        if (TYPE_SCAN.equals(jobType))
        {
            return mediaProperties.getScanTimeoutMinutes();
        }
        if (TYPE_THUMB.equals(jobType))
        {
            return mediaProperties.getThumbTimeoutMinutes();
        }
        if (TYPE_ENRICH.equals(jobType))
        {
            return mediaProperties.getEnrichTimeoutMinutes();
        }
        if (TYPE_EXPORT.equals(jobType))
        {
            return mediaProperties.getExportTimeoutMinutes();
        }
        if (TYPE_BACKFILL.equals(jobType) || TYPE_FIX_POSTER.equals(jobType))
        {
            return mediaProperties.getMaintainTimeoutMinutes();
        }
        return mediaProperties.getDeriveTimeoutMinutes();
    }

    /**
     * 工具路径注入（与 {@code MS_MYSQL} 同风格：非空才注入）
     *
     * <p>{@code MS_FFMPEG} 给 build-thumbs.js（替代脚本内默认的 runtime/ffmpeg.exe），
     * {@code MS_FFPROBE} 给 enrich-meta.js（探测 dur / w / h / vcodec）。留空时什么都不做，
     * 脚本按 {@code PATH} 自己找，不影响已有部署与环境里本已存在的同名变量。</p>
     */
    private void applyToolEnv(ProcessBuilder builder)
    {
        String ffmpeg = mediaProperties.getFfmpeg();
        if (StringUtils.isNotEmpty(ffmpeg))
        {
            builder.environment().put("MS_FFMPEG", ffmpeg.trim());
        }
        String ffprobe = mediaProperties.getFfprobe();
        if (StringUtils.isNotEmpty(ffprobe))
        {
            builder.environment().put("MS_FFPROBE", ffprobe.trim());
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
        // 逐条结果（「哪几条生成了」）：有 RESULT 行才给，前端可直接按 id 打标
        Map<String, Object> derive = deriveResultOf(job.getLog());
        if (derive != null)
        {
            view.put("derive", derive);
        }
        return view;
    }

    /**
     * 从 job 日志的 {@code RESULT ok= skip= fail= ids= [failIds=] [skipIds=] [truncated=1]} 行解析结构化结果
     *
     * <p>取<b>最后</b>一行（任务被重跑 / 日志里出现多次也只看最终那次）；没有就返回 null，
     * 保证旧任务的接口形状不变。</p>
     */
    private static Map<String, Object> deriveResultOf(String logText)
    {
        if (StringUtils.isEmpty(logText))
        {
            return null;
        }
        String found = null;
        for (String line : logText.split("\n"))
        {
            if (line.startsWith("RESULT ") && THUMB_RESULT.matcher(line).find())
            {
                found = line;
            }
        }
        if (found == null)
        {
            return null;
        }
        Matcher matcher = THUMB_RESULT.matcher(found);
        matcher.find();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Integer.parseInt(matcher.group(1)));
        out.put("skip", Integer.parseInt(matcher.group(2)));
        out.put("fail", Integer.parseInt(matcher.group(3)));
        out.put("ids", splitIds(matcher.group(4)));
        out.put("failIds", splitIds(fieldOf(found, "failIds=")));
        out.put("skipIds", splitIds(fieldOf(found, "skipIds=")));
        out.put("truncated", found.contains("truncated=1"));
        return out;
    }

    /** 取 {@code <name><值>} 这种空格分隔字段的值；没有返回空串 */
    private static String fieldOf(String line, String name)
    {
        int at = line.indexOf(' ' + name);
        if (at < 0)
        {
            return "";
        }
        String rest = line.substring(at + 1 + name.length());
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** 逗号分隔 id 串 → 列表（"-" 与空串都当空表） */
    private static List<String> splitIds(String csv)
    {
        List<String> ids = new ArrayList<>();
        if (csv == null || csv.isEmpty() || "-".equals(csv))
        {
            return ids;
        }
        for (String part : csv.split(","))
        {
            if (!part.isEmpty())
            {
                ids.add(part);
            }
        }
        return ids;
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
