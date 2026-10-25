package com.qtools.media.service.impl;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.config.MediaWatchConfig;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.domain.dto.MediaScanRequest;
import com.qtools.media.event.MediaScanFinishedEvent;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.service.IMediaScanService;
import com.qtools.media.service.IMediaWatchConfigService;
import com.qtools.media.service.IMediaWatchService;
import com.qtools.media.support.MediaQuietWindow;

/**
 * 文件监听（自动增量扫描）服务实现
 *
 * <h2>两层配置（本类只管运行期那一层）</h2>
 * <ul>
 *   <li><b>部署层</b>（yml，需重启）：{@code qtools.media.watch.allowRuntimeToggle} 是能力总闸——
 *       false 时 {@code PUT /watch/config} 一律 403、{@code pause/resume} 也不可用，只能改库后重启；
 *       {@code debounceSeconds} / {@code quietMinutes} 只作 {@code sys_config} 缺失 / 非法时的兜底。</li>
 *   <li><b>运行期</b>（{@code sys_config}，改完即时生效）：{@code media.watchEnabled} 决定「听不听」，
 *       {@code media.watchDebounceSeconds} / {@code media.watchQuietMinutes} 决定「合多久、夜间静不静」。
 *       读取带 30s 缓存（{@link IMediaWatchConfigService}），写后立即失效。</li>
 * </ul>
 *
 * <h2>机制</h2>
 * <ol>
 *   <li><b>常驻 Bean</b>：本类<b>不再</b>由 {@code @ConditionalOnProperty} 决定是否注册。启动时按
 *       {@code media.watchEnabled} 调用 {@link #applyConfig()}：false 时不注册任何 WatchService
 *       （不建线程、不占句柄），但 bean 存在、{@code GET /watch} 可查、{@code scan-now} 可用。</li>
 *   <li><b>一个 WatchService，多根多目录</b>：{@code media_root} 里每个 {@code kind='root'} 且存在的根
 *       递归注册（{@code ENTRY_CREATE / ENTRY_DELETE / ENTRY_MODIFY}）；新建目录在
 *       {@code ENTRY_CREATE} 到来时就地递归注册，因此「后建的相册目录」也能收到事件。</li>
 *   <li><b>去抖</b>：事件只更新该根的计数与时间戳，并把一次「flush」推迟 {@code debounceSeconds}
 *       秒；新事件到来就取消并重排，因此窗口内的 N 个事件合并成 <b>1 次</b> 扫描。去抖秒数与静默区间
 *       每次都从 {@link IMediaWatchConfigService} 现取，所以运行期改参数对<b>下一个事件</b>立即生效。</li>
 *   <li><b>不并发</b>：flush 调既有的 {@code IMediaScanService.start(mode=scan)}；它本身有「同一时刻
 *       仅 1 个任务」的保护，抛 409 时本类只置 {@code pending}，等
 *       {@link MediaScanFinishedEvent} 到了再补跑 <b>1 次</b>（补跑同样走 409 检查，不会并发）。</li>
 *   <li><b>来源可辨</b>：自动触发把 {@code origin=AUTO} 与「路径 变化 N 个事件」写进 job 日志首行，
 *       手工触发没有这一行。</li>
 *   <li><b>不污染主流程</b>：监听线程的每层循环都 catch 并 warn；启动失败/注册失败只降级，
 *       绝不让应用启动失败；运行期开关失败只把状态留在「未运行 + hint」，同样不抛给上层。</li>
 * </ol>
 *
 * <h2>忽略规则</h2>
 * <p>{@code *.tmp / *.part / *.crdownload / ~$*} 以及路径里出现 {@code _媒体站} 的任何对象；
 * 另外<b>目录自身的 MODIFY 事件也忽略</b>——Windows 上「往目录里写一个文件」会同时给目录发一条
 * MODIFY，不挡掉的话 {@code *.tmp} 永远过滤不干净。</p>
 *
 * @author qtools
 */
@Service
public class MediaWatchService implements IMediaWatchService
{
    private static final Logger log = LoggerFactory.getLogger(MediaWatchService.class);

    /** 自动扫描来源标识：写进 {@code media_scan_job.log} 首行 */
    private static final String ORIGIN_AUTO = "AUTO";

    /** 临时文件后缀：下载中 / 编码中 / 分片中的产物一律忽略 */
    private static final List<String> IGNORE_SUFFIXES = List.of(".tmp", ".part", ".crdownload");

    /** 锁文件前缀（Office / 部分编辑器） */
    private static final String IGNORE_PREFIX = "~$";

    /** 媒体站自己的内部目录（回收站等），永远不监听 */
    private static final String INTERNAL_DIR = "_媒体站";

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 部署层封掉运行期开关时的 403 文案（前端直接展示，因此定稿在这里） */
    public static final String TOGGLE_FORBIDDEN_MESSAGE = "部署层已禁止运行期开关（qtools.media.watch.allowRuntimeToggle=false），"
            + "本次修改未生效；如需切换请改 sys_config.media.watchEnabled 后重启";

    private final MediaProperties mediaProperties;

    private final IMediaScanService mediaScanService;

    private final MediaRootMapper mediaRootMapper;

    private final IMediaWatchConfigService watchConfigService;

    /** 一个 JVM 一个 WatchService：所有根、所有子目录都注册在它上面（未运行时为 null） */
    private volatile WatchService watchService;

    private volatile Thread watchThread;

    /** 去抖计时器（单线程）：合并动作是毫秒级的，真正耗时的扫描在 media-scan 线程里 */
    private volatile ScheduledExecutorService debounceScheduler;

    /** WatchKey → 所属根状态（子目录的 key 也指向同一个根） */
    private final Map<WatchKey, RootState> keyToRoot = new ConcurrentHashMap<>();

    /** 已注册的根，key = path 文本；顺序按注册先后 */
    private final Map<String, RootState> roots = new ConcurrentHashMap<>();

    private final AtomicBoolean paused = new AtomicBoolean(false);

    /** 监听是否在跑（WatchService 已创建且监听线程已启动） */
    private volatile boolean running;

    /** start / stop 互斥；applyConfig 的读-判-改序列不会和另一个 applyConfig 交错 */
    private final Object lifecycleLock = new Object();

    public MediaWatchService(MediaProperties mediaProperties, IMediaScanService mediaScanService,
            MediaRootMapper mediaRootMapper, IMediaWatchConfigService watchConfigService)
    {
        this.mediaProperties = mediaProperties;
        this.mediaScanService = mediaScanService;
        this.mediaRootMapper = mediaRootMapper;
        this.watchConfigService = watchConfigService;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 启动时按 {@code sys_config.media.watchEnabled} 决定听不听
     *
     * <p>读库 / 建 WatchService / 注册根任何一步失败都只 warn：监听是锦上添花，绝不能让应用启动失败。</p>
     */
    @PostConstruct
    public void init()
    {
        try
        {
            applyConfig();
        }
        catch (Exception e)
        {
            log.warn("媒体库文件监听初始化失败（自动扫描不可用，其余功能不受影响）", e);
        }
    }

    @PreDestroy
    public void shutdown()
    {
        log.info("媒体库文件监听关闭中");
        stopListener("应用关闭");
    }

    /**
     * 让<b>运行期状态</b>追上 {@code sys_config}：开 → 注册监听，关 → 停掉并清理，改参数 → 由下一次事件现取
     *
     * <p>幂等：已经在跑且仍然要跑时不做任何启停动作（参数变更不需要重启监听，因为去抖 / 静默都是
     * 每次现取）。启动失败不抛异常，只留在「enabled=true / running=false」并由 {@link #status()} 的
     * {@code hint} 说明。</p>
     */
    @Override
    public Map<String, Object> applyConfig()
    {
        MediaWatchConfig config = watchConfigService.current();
        if (config.isEnabled())
        {
            if (running)
            {
                log.debug("自动扫描参数变更已生效（无需重启监听）：{}", config);
            }
            else
            {
                start();
            }
        }
        else if (running)
        {
            stopListener("运行期关闭（media.watchEnabled=false）");
        }
        return status();
    }

    /** 开监听：建 WatchService + 去抖线程 + 注册 kind=root 的根 + 起监听线程；失败返回 false */
    private boolean start()
    {
        synchronized (lifecycleLock)
        {
            if (running)
            {
                return true;
            }
            WatchService service;
            ScheduledExecutorService scheduler;
            try
            {
                service = FileSystems.getDefault().newWatchService();
                scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "media-watch-debounce");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            catch (Exception e)
            {
                log.warn("媒体库文件监听启动失败（自动扫描不可用，其余功能不受影响）", e);
                return false;
            }
            watchService = service;
            debounceScheduler = scheduler;
            keyToRoot.clear();
            roots.clear();
            // 每次「开」都从「未暂停」开始：不要把上一轮暂停的残影带到新的一轮
            paused.set(false);
            int registered;
            try
            {
                registered = registerRoots(service);
            }
            catch (Exception e)
            {
                log.warn("媒体根注册监听失败（自动扫描本次可能收不到事件）", e);
                registered = 0;
            }
            Thread thread = new Thread(() -> watchLoop(service), "media-watch");
            thread.setDaemon(true);
            watchThread = thread;
            running = true;
            thread.start();
            MediaWatchConfig config = watchConfigService.current();
            log.info("媒体库文件监听已启动：注册根 {} 个，去抖 {}s，静默 {}", registered,
                    config.getDebounceSeconds(), describeQuiet(config));
            return true;
        }
    }

    /** 停监听并清理（幂等，可重复调用） */
    private void stopListener(String reason)
    {
        synchronized (lifecycleLock)
        {
            boolean wasRunning = running;
            running = false;
            WatchService service = watchService;
            watchService = null;
            if (service != null)
            {
                try
                {
                    // close() 让阻塞中的 take() 抛 ClosedWatchServiceException，监听线程随即退出
                    service.close();
                }
                catch (IOException e)
                {
                    log.debug("关闭 WatchService 失败", e);
                }
            }
            ScheduledExecutorService scheduler = debounceScheduler;
            debounceScheduler = null;
            if (scheduler != null)
            {
                scheduler.shutdownNow();
            }
            Thread thread = watchThread;
            watchThread = null;
            if (thread != null && thread != Thread.currentThread())
            {
                thread.interrupt();
            }
            keyToRoot.clear();
            for (RootState state : roots.values())
            {
                synchronized (state)
                {
                    state.flushFuture = null;
                    state.pendingEvents = 0;
                    state.pendingScan = false;
                }
            }
            roots.clear();
            if (wasRunning)
            {
                log.info("媒体库文件监听已停止（{}）", reason);
            }
        }
    }

    // ------------------------------------------------------------------ 注册

    /** 注册全部 {@code kind='root'} 且存在的媒体根，返回成功注册的根数 */
    private int registerRoots(WatchService service)
    {
        int registered = 0;
        for (MediaRoot root : mediaRootMapper.selectRootList())
        {
            if (root == null || !"root".equalsIgnoreCase(root.getKind()) || StringUtils.isEmpty(root.getPath()))
            {
                continue;
            }
            Path path = toPath(root.getPath());
            if (path == null || !Files.isDirectory(path))
            {
                log.warn("媒体根不存在或不是目录，跳过监听 root={} path={}", root.getId(), root.getPath());
                continue;
            }
            RootState state = new RootState(root.getId(), path);
            roots.put(state.pathText, state);
            registerTree(service, state, path);
            registered++;
        }
        return registered;
    }

    /** 递归注册一个根 / 新目录下的所有目录（跳过 {@code _媒体站}） */
    private void registerTree(WatchService service, RootState state, Path start)
    {
        try
        {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                {
                    if (!dir.equals(start) && isInternal(dir))
                    {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try
                    {
                        WatchKey key = dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                        keyToRoot.put(key, state);
                        return FileVisitResult.CONTINUE;
                    }
                    catch (IOException e)
                    {
                        log.warn("目录注册监听失败，跳过 dir={}（不影响该根其它目录）", dir, e);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
            });
            state.registered = true;
        }
        catch (Exception e)
        {
            log.warn("递归注册监听失败 root={}", state.pathText, e);
        }
    }

    // ------------------------------------------------------------------ 监听主循环

    /** 监听循环；{@code service} 是本轮启动时创建的那个，重启后旧循环自己退出 */
    private void watchLoop(WatchService service)
    {
        while (running && watchService == service)
        {
            WatchKey key;
            try
            {
                key = service.take();
            }
            catch (ClosedWatchServiceException e)
            {
                return;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception e)
            {
                log.warn("监听取事件异常（继续）", e);
                continue;
            }
            RootState state = keyToRoot.get(key);
            try
            {
                if (state == null)
                {
                    key.reset();
                    continue;
                }
                Path dir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents())
                {
                    try
                    {
                        handleEvent(service, state, dir, event);
                    }
                    catch (Exception e)
                    {
                        log.warn("监听事件处理失败 root={} dir={} kind={}", state.pathText, dir, event.kind(), e);
                    }
                }
                if (!key.reset())
                {
                    keyToRoot.remove(key);
                }
            }
            catch (Throwable t)
            {
                // 监听线程的异常绝不允许冒出去：JDK 默认处理器会把它吞掉且不留日志
                log.warn("监听主循环异常（继续）", t);
            }
        }
    }

    private void handleEvent(WatchService service, RootState state, Path dir, WatchEvent<?> event) throws IOException
    {
        WatchEvent.Kind<?> kind = event.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW)
        {
            // 队列溢出：细节已经丢了，只能整根重扫一次
            onChanged(state, dir);
            return;
        }
        Object context = event.context();
        if (!(context instanceof Path))
        {
            return;
        }
        Path child = dir.resolve((Path) context);
        if (isIgnored(child))
        {
            log.debug("忽略临时文件/内部目录事件 path={}", child);
            return;
        }
        boolean directory = Files.isDirectory(child);
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY)
        {
            // 目录自身的 MODIFY 是「里面某个文件被写」的噪声，文件自身的 MODIFY 才是真事件
            if (!directory)
            {
                onChanged(state, child);
            }
            return;
        }
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && directory)
        {
            // 新目录：立刻递归注册它的子树，否则新相册里的文件收不到任何事件
            registerTree(service, state, child);
        }
        onChanged(state, child);
    }

    // ------------------------------------------------------------------ 去抖 / 触发

    private void onChanged(RootState state, Path path)
    {
        state.lastEventAt = System.currentTimeMillis();
        state.lastEventPath = path.toString().replace('\\', '/');
        if (paused.get())
        {
            // 暂停期间事件直接丢弃（恢复后按需扫描）；只留 lastEventAt 供状态页看「目录还在动」
            log.debug("监听已暂停，忽略事件 root={} path={}", state.pathText, state.lastEventPath);
            return;
        }
        synchronized (state)
        {
            state.pendingEvents++;
            scheduleFlush(state);
        }
    }

    /** 重置去抖计时（调用方持有 state 锁） */
    private void scheduleFlush(RootState state)
    {
        ScheduledExecutorService scheduler = debounceScheduler;
        if (!running || scheduler == null)
        {
            return;
        }
        // 去抖秒数每次现取：运行期改 debounceSeconds 对下一个事件立即生效
        long delay = watchConfigService.getDebounceSeconds() * 1000L;
        // 夜间静默：把合并后的扫描推迟到静默区间结束
        long quiet = quietMillisRemaining();
        long effective = quiet > 0 ? Math.max(delay, quiet) : delay;
        if (state.flushFuture != null)
        {
            state.flushFuture.cancel(false);
        }
        state.flushFuture = scheduler.schedule(() -> flush(state), effective, TimeUnit.MILLISECONDS);
    }

    /** 去抖窗口到期：把攒下的事件合并成一次扫描 */
    private void flush(RootState state)
    {
        int count;
        synchronized (state)
        {
            state.flushFuture = null;
            count = state.pendingEvents;
            if (count == 0)
            {
                return;
            }
            if (!running)
            {
                state.pendingEvents = 0;
                return;
            }
            long quiet = quietMillisRemaining();
            if (quiet > 0)
            {
                ScheduledExecutorService scheduler = debounceScheduler;
                if (scheduler != null)
                {
                    state.flushFuture = scheduler.schedule(() -> flush(state), quiet, TimeUnit.MILLISECONDS);
                    log.info("夜间静默中，自动扫描推迟 {}s（root={} 事件={}）", quiet / 1000, state.pathText, count);
                }
                return;
            }
            state.pendingEvents = 0;
        }
        triggerScan(state, state.pathText + " 变化 " + count + " 个事件");
    }

    /**
     * 发起一次 {@code mode=scan}：走既有 start 接口，因此并发保护 / 日志 / 超时全部复用
     *
     * <p>已有任务在跑 → 409 → 只置 pending，等 {@link MediaScanFinishedEvent} 补跑一次。</p>
     */
    private void triggerScan(RootState state, String note)
    {
        MediaScanRequest request = new MediaScanRequest(null, "scan", false, null);
        request.setOrigin(ORIGIN_AUTO);
        request.setTriggerNote(note);
        try
        {
            mediaScanService.start(request);
            state.lastScanAt = System.currentTimeMillis();
            synchronized (state)
            {
                state.pendingScan = false;
            }
            log.info("自动扫描已触发：{}", note);
        }
        catch (MediaException e)
        {
            if (e.getStatus() == 409)
            {
                synchronized (state)
                {
                    state.pendingScan = true;
                }
                log.info("已有扫描任务在运行，置 pending，本次结束后补跑一次：{}", note);
            }
            else
            {
                log.warn("自动扫描未触发 status={} {}：{}", e.getStatus(), note, e.getMessage());
            }
        }
        catch (Exception e)
        {
            log.warn("自动扫描触发异常：{}", note, e);
        }
    }

    /**
     * 扫描结束 → 补跑 pending 的那个根
     *
     * <p>事件是 {@code media-scan} 线程里同步派发的，所以补跑必须交给去抖线程，
     * 不能在这个监听方法里直接再起任务。</p>
     */
    @EventListener
    public void onScanFinished(MediaScanFinishedEvent event)
    {
        ScheduledExecutorService scheduler = debounceScheduler;
        if (!running || scheduler == null)
        {
            return;
        }
        List<RootState> retry = new ArrayList<>();
        for (RootState state : roots.values())
        {
            boolean pending;
            synchronized (state)
            {
                pending = state.pendingScan;
                state.pendingScan = false;
            }
            if (pending)
            {
                retry.add(state);
            }
        }
        if (retry.isEmpty())
        {
            return;
        }
        if (paused.get())
        {
            log.info("监听已暂停，丢弃 {} 个根的补跑 pending", retry.size());
            return;
        }
        log.info("扫描任务 {} 结束（{}），补跑 {} 个根的 pending", event.getJobId(), event.getStatus(), retry.size());
        for (RootState state : retry)
        {
            scheduler.execute(() -> triggerScan(state, state.pathText + " 补跑（上次因任务在忙置 pending）"));
        }
    }

    // ------------------------------------------------------------------ 运行期配置

    /**
     * 运行期改配置：body {@code {enabled?, debounceSeconds?, quietMinutes?}}，落库后立即 {@link #applyConfig()}
     *
     * <p>三层判断顺序固定：<b>能力总闸</b>（403）→ <b>取值范围</b>（400）→ 落库 + 生效。
     * 总闸先判，所以「封掉了运行期开关」的实例不会因为参数非法先收到 400，
     * 前端拿到的永远是「部署层禁止」这一条。</p>
     */
    @Override
    public Map<String, Object> updateConfig(Map<String, Object> body)
    {
        if (!isRuntimeToggleAllowed())
        {
            throw new MediaException(403, TOGGLE_FORBIDDEN_MESSAGE);
        }
        Map<String, Object> patch = body == null ? Map.of() : body;
        Boolean enabled = patch.containsKey("enabled") ? parseEnabled(patch.get("enabled")) : null;
        Integer debounceSeconds = patch.containsKey("debounceSeconds")
                ? parseDebounceSeconds(patch.get("debounceSeconds")) : null;
        boolean quietPresent = patch.containsKey("quietMinutes");
        String quietMinutes = quietPresent ? MediaQuietWindow.requireValid(stringValueOf(patch.get("quietMinutes"))) : null;
        if (enabled == null && debounceSeconds == null && !quietPresent)
        {
            throw MediaException.badRequest("没有可改的字段：body 至少给 enabled / debounceSeconds / quietMinutes 之一");
        }
        watchConfigService.save(enabled, debounceSeconds, quietPresent ? quietMinutes : null);
        log.info("自动扫描配置已更新：enabledPatch={} debounceSecondsPatch={} quietMinutesPatch={}",
                enabled, debounceSeconds, quietMinutes);
        return applyConfig();
    }

    @Override
    public boolean isRuntimeToggleAllowed()
    {
        MediaProperties.Watch watch = mediaProperties.getWatch();
        return watch == null || watch.isAllowRuntimeToggle();
    }

    /** enabled：接受 true / false / 1 / 0；其余 400 */
    private static Boolean parseEnabled(Object value)
    {
        if (value instanceof Boolean flag)
        {
            return flag;
        }
        String text = stringValueOf(value);
        if (text == null)
        {
            throw MediaException.badRequest("enabled 只能是 true / false");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "1".equals(lower))
        {
            return Boolean.TRUE;
        }
        if ("false".equals(lower) || "0".equals(lower))
        {
            return Boolean.FALSE;
        }
        throw MediaException.badRequest("enabled 只能是 true / false，本次: " + text);
    }

    /** debounceSeconds：整数且在 1-60；非法 400 */
    private static Integer parseDebounceSeconds(Object value)
    {
        Integer parsed;
        try
        {
            parsed = intValueOf(value);
        }
        catch (MediaException e)
        {
            parsed = null;
        }
        if (parsed == null)
        {
            throw MediaException.badRequest("debounceSeconds 必须是 "
                    + MediaWatchConfigServiceImpl.MIN_DEBOUNCE_SECONDS + "-"
                    + MediaWatchConfigServiceImpl.MAX_DEBOUNCE_SECONDS + " 的整数，本次: " + value);
        }
        if (parsed.intValue() < MediaWatchConfigServiceImpl.MIN_DEBOUNCE_SECONDS
                || parsed.intValue() > MediaWatchConfigServiceImpl.MAX_DEBOUNCE_SECONDS)
        {
            throw MediaException.badRequest("debounceSeconds 只能是 "
                    + MediaWatchConfigServiceImpl.MIN_DEBOUNCE_SECONDS + "-"
                    + MediaWatchConfigServiceImpl.MAX_DEBOUNCE_SECONDS + "，本次: " + parsed);
        }
        return parsed;
    }

    /** 数字 / 数字字符串 → Integer；其余返回 null（由调用方决定 400 文案） */
    private static Integer intValueOf(Object value)
    {
        if (value instanceof Number number)
        {
            return Integer.valueOf(number.intValue());
        }
        String text = stringValueOf(value);
        if (text == null)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** 去首尾空格；null / 空串 / 字面 "null" → null */
    private static String stringValueOf(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text))
        {
            return null;
        }
        return text;
    }

    // ------------------------------------------------------------------ 手工控制

    @Override
    public Map<String, Object> status()
    {
        MediaWatchConfig config = watchConfigService.current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("allowed", isRuntimeToggleAllowed());
        view.put("enabled", config.isEnabled());
        view.put("running", running);
        view.put("paused", paused.get());
        view.put("debounceSeconds", config.getDebounceSeconds());
        view.put("quietMinutes", config.getQuietMinutes());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RootState state : roots.values())
        {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", state.rootId);
            node.put("path", state.pathText);
            synchronized (state)
            {
                node.put("registered", state.registered);
                node.put("lastEventAt", formatDate(state.lastEventAt));
                node.put("lastScanAt", formatDate(state.lastScanAt));
                node.put("pending", state.pendingScan);
                node.put("pendingEvents", state.pendingEvents);
            }
            nodes.add(node);
        }
        view.put("roots", nodes);
        view.put("hint", hint(config));
        return view;
    }

    /** 状态页顶部的可读提示：说明「为什么现在不跑」以及下一步该调哪个接口 */
    private String hint(MediaWatchConfig config)
    {
        if (!isRuntimeToggleAllowed())
        {
            return "部署层已禁止运行期开关（qtools.media.watch.allowRuntimeToggle=false）："
                    + "只能直接改 sys_config.media.watchEnabled 后重启；pause / resume 同样不可用";
        }
        if (!config.isEnabled())
        {
            return "自动扫描已关闭：PUT /api/media/admin/watch/config {enabled:true} 立即开启，无需重启"
                    + "（在此之前可用 POST /api/media/admin/watch/scan-now 手动触发一次扫描）";
        }
        if (!running)
        {
            return "自动扫描已开启但监听未挂载（启动失败，详见后端日志）：检查媒体根是否存在 / 是否有监听句柄权限，"
                    + "修好后 PUT /api/media/admin/watch/config {enabled:true} 重试";
        }
        if (paused.get())
        {
            return "监听已暂停（运行期状态，不写配置）：POST /api/media/admin/watch/resume 恢复";
        }
        return "";
    }

    /**
     * 暂停：只在「开了且真的在跑」时可用
     *
     * <p>{@code media.watchEnabled=false} 时保持既有语义——409 + 可读文案，而不是静默返回一个假状态。</p>
     */
    @Override
    public Map<String, Object> pause()
    {
        requireRunning("pause");
        paused.set(true);
        log.info("媒体库文件监听已暂停（运行期开关，不写配置）");
        return status();
    }

    @Override
    public Map<String, Object> resume()
    {
        requireRunning("resume");
        paused.set(false);
        log.info("媒体库文件监听已恢复");
        return status();
    }

    /** pause / resume 的前置检查：没开 / 没跑都明确 409 */
    private void requireRunning(String action)
    {
        if (!watchConfigService.isEnabled())
        {
            throw MediaException.conflict("自动扫描已关闭（sys_config.media.watchEnabled=false），"
                    + action + " 无对象可操作；先 PUT /api/media/admin/watch/config {enabled:true} 开启");
        }
        if (!running)
        {
            throw MediaException.conflict("文件监听未挂载（启动失败或已停止），" + action + " 无对象可操作；"
                    + "检查媒体根与监听句柄后 PUT /api/media/admin/watch/config {enabled:true} 重试");
        }
    }

    @Override
    public Map<String, Object> scanNow()
    {
        // 「立即扫一次」等价管理端按钮：不走去抖、不受暂停影响，但同样受「同一时刻仅 1 个任务」约束（409 原样抛出）
        MediaScanRequest request = new MediaScanRequest(null, "scan", false, null);
        Map<String, Object> view = mediaScanService.start(request);
        long now = System.currentTimeMillis();
        for (RootState state : roots.values())
        {
            state.lastScanAt = now;
            synchronized (state)
            {
                state.pendingScan = false;
            }
        }
        return view;
    }

    // ------------------------------------------------------------------ 忽略规则 / 静默区间

    /** 忽略：锁文件前缀 / 临时文件后缀 / 任何一段是 {@code _媒体站} 的路径 */
    private static boolean isIgnored(Path path)
    {
        Path fileName = path.getFileName();
        if (fileName == null)
        {
            return true;
        }
        String name = fileName.toString();
        if (name.startsWith(IGNORE_PREFIX))
        {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : IGNORE_SUFFIXES)
        {
            if (lower.endsWith(suffix))
            {
                return true;
            }
        }
        return isInternal(path);
    }

    private static boolean isInternal(Path path)
    {
        for (Path part : path)
        {
            if (INTERNAL_DIR.equals(part.toString()))
            {
                return true;
            }
        }
        return false;
    }

    /** 距夜间静默区间结束还有多少毫秒；不在区间内（或配置非法）返回 0 */
    private long quietMillisRemaining()
    {
        int[] window;
        try
        {
            // 静默区间每次现取：运行期改 quietMinutes 对下一次判定立即生效
            window = MediaQuietWindow.parse(watchConfigService.getQuietMinutes());
        }
        catch (Exception e)
        {
            log.warn("静默区间解析异常，按不静默处理", e);
            return 0L;
        }
        return MediaQuietWindow.millisRemaining(window, LocalTime.now());
    }

    private String describeQuiet(MediaWatchConfig config)
    {
        String raw = config == null || config.getQuietMinutes() == null ? "" : config.getQuietMinutes();
        return raw.isEmpty() ? "（不静默）" : raw;
    }

    // ------------------------------------------------------------------ 小工具

    private static Path toPath(String text)
    {
        try
        {
            return Paths.get(text.trim());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String formatDate(long epochMillis)
    {
        return epochMillis <= 0L ? null : DateUtils.parseDateToStr(DATE_PATTERN, new Date(epochMillis));
    }

    /** 一个媒体根的监听状态（该根下所有子目录的 WatchKey 都指向它） */
    private static final class RootState
    {
        private final Integer rootId;

        private final String pathText;

        private volatile boolean registered;

        private volatile long lastEventAt;

        private volatile long lastScanAt;

        private volatile String lastEventPath;

        /** 已攒下但还没合并触发的事件数（持 state 锁读写） */
        private int pendingEvents;

        /** 已有任务在跑时置 true，当前任务结束后补跑一次 */
        private boolean pendingScan;

        private ScheduledFuture<?> flushFuture;

        private RootState(Integer rootId, Path path)
        {
            this.rootId = rootId;
            this.pathText = path.toString().replace('\\', '/');
        }
    }
}
