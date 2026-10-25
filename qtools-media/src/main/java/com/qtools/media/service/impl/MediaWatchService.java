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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.domain.dto.MediaScanRequest;
import com.qtools.media.event.MediaScanFinishedEvent;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaRootMapper;
import com.qtools.media.service.IMediaScanService;
import com.qtools.media.service.IMediaWatchService;

/**
 * 文件监听（自动增量扫描）服务实现
 *
 * <h2>机制</h2>
 * <ol>
 *   <li><b>一个 WatchService，多根多目录</b>：{@code media_root} 里每个 {@code kind='root'} 且存在的根
 *       递归注册（{@code ENTRY_CREATE / ENTRY_DELETE / ENTRY_MODIFY}）；新建目录在
 *       {@code ENTRY_CREATE} 到来时就地递归注册，因此「后建的相册目录」也能收到事件。</li>
 *   <li><b>去抖</b>：事件只更新该根的计数与时间戳，并把一次「flush」推迟 {@code debounceSeconds}
 *       秒；新事件到来就取消并重排，因此窗口内的 N 个事件合并成 <b>1 次</b> 扫描。</li>
 *   <li><b>不并发</b>：flush 调既有的 {@code IMediaScanService.start(mode=scan)}；它本身有「同一时刻
 *       仅 1 个任务」的保护，抛 409 时本类只置 {@code pending}，等
 *       {@link MediaScanFinishedEvent} 到了再补跑 <b>1 次</b>（补跑同样走 409 检查，不会并发）。</li>
 *   <li><b>来源可辨</b>：自动触发把 {@code origin=AUTO} 与「路径 变化 N 个事件」写进 job 日志首行，
 *       手工触发没有这一行。</li>
 *   <li><b>不污染主流程</b>：监听线程的每层循环都 catch 并 warn；启动失败/注册失败只降级，
 *       绝不让应用启动失败；{@code @PreDestroy} 关闭 WatchService 并中断线程。</li>
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
@ConditionalOnProperty(prefix = "qtools.media.watch", name = "enabled", havingValue = "true")
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

    private final MediaProperties mediaProperties;

    private final IMediaScanService mediaScanService;

    private final MediaRootMapper mediaRootMapper;

    /** 一个 JVM 一个 WatchService：所有根、所有子目录都注册在它上面 */
    private WatchService watchService;

    private Thread watchThread;

    /** 去抖计时器（单线程）：合并动作是毫秒级的，真正耗时的扫描在 media-scan 线程里 */
    private ScheduledExecutorService debounceScheduler;

    /** WatchKey → 所属根状态（子目录的 key 也指向同一个根） */
    private final Map<WatchKey, RootState> keyToRoot = new ConcurrentHashMap<>();

    /** 已注册的根，key = path 文本；顺序按注册先后 */
    private final Map<String, RootState> roots = new ConcurrentHashMap<>();

    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile boolean running;

    private volatile boolean closed;

    public MediaWatchService(MediaProperties mediaProperties, IMediaScanService mediaScanService,
            MediaRootMapper mediaRootMapper)
    {
        this.mediaProperties = mediaProperties;
        this.mediaScanService = mediaScanService;
        this.mediaRootMapper = mediaRootMapper;
    }

    // ------------------------------------------------------------------ 生命周期

    @PostConstruct
    public void init()
    {
        MediaProperties.Watch config = mediaProperties.getWatch();
        if (config == null || !config.isEnabled())
        {
            return;
        }
        try
        {
            watchService = FileSystems.getDefault().newWatchService();
            debounceScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "media-watch-debounce");
                thread.setDaemon(true);
                return thread;
            });
            int registered = registerRoots();
            watchThread = new Thread(this::watchLoop, "media-watch");
            watchThread.setDaemon(true);
            watchThread.start();
            running = true;
            log.info("媒体库文件监听已启动：注册根 {} 个，去抖 {}s，静默区间 {}", registered,
                    config.getDebounceSeconds(), describeQuiet());
        }
        catch (Exception e)
        {
            // 监听只是锦上添花：起不来也绝不能让应用启动失败
            log.warn("媒体库文件监听启动失败（自动扫描不可用，其余功能不受影响）", e);
            closeQuietly();
        }
    }

    @PreDestroy
    public void shutdown()
    {
        log.info("媒体库文件监听关闭中");
        closeQuietly();
    }

    private void closeQuietly()
    {
        closed = true;
        running = false;
        WatchService service = watchService;
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
        if (scheduler != null)
        {
            scheduler.shutdownNow();
        }
        Thread thread = watchThread;
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
            }
        }
    }

    // ------------------------------------------------------------------ 注册

    /** 注册全部 {@code kind='root'} 且存在的媒体根，返回成功注册的根数 */
    private int registerRoots()
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
            registerTree(state, path);
            registered++;
        }
        return registered;
    }

    /** 递归注册一个根 / 新目录下的所有目录（跳过 {@code _媒体站}） */
    private void registerTree(RootState state, Path start)
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
                        WatchKey key = dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
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

    private void watchLoop()
    {
        while (!closed)
        {
            WatchKey key;
            try
            {
                key = watchService.take();
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
                        handleEvent(state, dir, event);
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

    private void handleEvent(RootState state, Path dir, WatchEvent<?> event) throws IOException
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
            registerTree(state, child);
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
        if (closed || scheduler == null)
        {
            return;
        }
        long delay = mediaProperties.getWatch().getDebounceSeconds() * 1000L;
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
            if (closed)
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
        if (closed || scheduler == null)
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

    // ------------------------------------------------------------------ 手工控制

    @Override
    public Map<String, Object> status()
    {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", true);
        view.put("running", running && !closed);
        view.put("paused", paused.get());
        view.put("debounceSeconds", mediaProperties.getWatch().getDebounceSeconds());
        view.put("quietMinutes", describeQuiet());
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
        return view;
    }

    @Override
    public Map<String, Object> pause()
    {
        paused.set(true);
        log.info("媒体库文件监听已暂停（运行期开关，不写配置）");
        return status();
    }

    @Override
    public Map<String, Object> resume()
    {
        paused.set(false);
        log.info("媒体库文件监听已恢复");
        return status();
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

    /** 距夜间静默区间结束还有多少毫秒；不在区间内返回 0 */
    private long quietMillisRemaining()
    {
        MediaProperties.Watch config = mediaProperties.getWatch();
        int[] window = parseQuietWindow(config == null ? null : config.getQuietMinutes());
        if (window == null)
        {
            return 0L;
        }
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        int start = window[0];
        int end = window[1];
        boolean inside = start <= end ? (minute >= start && minute < end) : (minute >= start || minute < end);
        if (!inside)
        {
            return 0L;
        }
        int remaining = minute < end ? end - minute : 24 * 60 - minute + end;
        if (remaining < 1)
        {
            remaining = 1;
        }
        // 少退 1 秒：避免恰好在区间边界上反复重排
        return remaining * 60_000L - 1000L;
    }

    /** {@code HH:mm-HH:mm} 或 {@code 起始分钟-结束分钟} → [start,end]；空/非法返回 null */
    private static int[] parseQuietWindow(String text)
    {
        if (text == null || text.trim().isEmpty())
        {
            return null;
        }
        String raw = text.trim();
        int dash = raw.indexOf('-');
        if (dash <= 0 || dash == raw.length() - 1)
        {
            log.warn("qtools.media.watch.quietMinutes 格式非法（应为 HH:mm-HH:mm 或 起始分钟-结束分钟），已忽略：{}", raw);
            return null;
        }
        Integer start = minuteOf(raw.substring(0, dash));
        Integer end = minuteOf(raw.substring(dash + 1));
        if (start == null || end == null)
        {
            log.warn("qtools.media.watch.quietMinutes 取值越界，已忽略：{}", raw);
            return null;
        }
        return new int[] { start.intValue(), end.intValue() };
    }

    private static Integer minuteOf(String token)
    {
        String text = token.trim();
        int colon = text.indexOf(':');
        try
        {
            if (colon > 0)
            {
                int hour = Integer.parseInt(text.substring(0, colon).trim());
                int minute = Integer.parseInt(text.substring(colon + 1).trim());
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59)
                {
                    return null;
                }
                return Integer.valueOf(hour * 60 + minute);
            }
            int value = Integer.parseInt(text);
            return value >= 0 && value <= 1440 ? Integer.valueOf(value) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private String describeQuiet()
    {
        MediaProperties.Watch config = mediaProperties.getWatch();
        String raw = config == null ? null : config.getQuietMinutes();
        return StringUtils.isEmpty(raw) ? "" : raw.trim();
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
