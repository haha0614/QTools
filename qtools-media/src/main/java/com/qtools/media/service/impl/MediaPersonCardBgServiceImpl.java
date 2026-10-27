package com.qtools.media.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaPerson;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaPersonMapper;
import com.qtools.media.service.IMediaFileService;
import com.qtools.media.service.IMediaFrameService;
import com.qtools.media.service.IMediaPersonCardBgService;
import com.qtools.media.support.MediaAvatarCrop;
import com.qtools.media.support.MediaPersonCardBg;

/**
 * 人物卡背景预渲染服务实现（P5.90）
 *
 * <p>四步：解析人物头像来源 → 取有效参数（请求参数 &gt; 已保存的 bg_scale/bg_dx/bg_dy &gt; 自动几何）
 * → 拼内容哈希定位缓存文件 → 命中读盘、未命中渲染落盘。</p>
 *
 * <p><b>并发</b>：同一 key 的并发未命中会各渲染一次（最后一次原子替换胜出），字节相同，
 * 不会出现半张图 —— 写临时文件再 {@code ATOMIC_MOVE}。</p>
 *
 * <p><b>清理</b>：命中过期的判定是文件 mtime；命中时若 mtime 超过 1 天会 touch 一次，
 * 因此「30 天未被访问」真的等于没人看。清理在渲染后顺带跑（6 小时最多一次），
 * 不需要外部定时任务，也不需要人工 {@code --gc}。</p>
 *
 * @author qtools
 */
@Service
public class MediaPersonCardBgServiceImpl implements IMediaPersonCardBgService
{
    private static final Logger log = LoggerFactory.getLogger(MediaPersonCardBgServiceImpl.class);

    /** 缓存子目录（相对 {@code qtools.media.thumbsPath}） */
    public static final String CACHE_DIR = "cardbg";

    /** 输出上限：再大也不是「卡片」了，只会白烧 CPU 与磁盘（超出按比例缩，不报错） */
    public static final int MAX_W = 1200;

    public static final int MAX_H = 600;

    /** 缺省尺寸：333×102 CSS 像素 @2x DPR（人物墙 1600 视口下的实测卡片） */
    public static final int DEFAULT_W = 666;

    public static final int DEFAULT_H = 204;

    /** jpeg 质量（0.82 是「皮肤 / 渐变都不出块」的下限，再高只是白占带宽） */
    private static final float QUALITY = 0.82f;

    /** 未被访问的缓存文件保留 30 天 */
    private static final long CACHE_TTL_MS = 30L * 24 * 3600 * 1000;

    /** 命中时 mtime 超过这个值才回写（避免每个请求都写一次元数据） */
    private static final long TOUCH_AFTER_MS = 24L * 3600 * 1000;

    /** 清理的最小间隔 */
    private static final long PURGE_INTERVAL_MS = 6L * 3600 * 1000;

    /**
     * 渲染版本号：进内容哈希
     *
     * <p>改了渲染管线（模糊算法 / 合成顺序 / 常量）就必须 +1 —— 否则旧缓存文件仍然命中，
     * 用户看到的还是上一版像素，而且「清了浏览器缓存也没用」。这是内容寻址缓存唯一的版本阀门。</p>
     */
    private static final int RENDER_VERSION = 2;

    private static final AtomicLong LAST_PURGE = new AtomicLong(0);

    /** P5.92 预热：后台线程数（2–4 个：再多只是把同一颗 CPU 抢得更碎） */
    private static final int WARM_THREADS = 3;

    /** P5.92 预热队列上限（满了就丢：预热本来就是「锦上添花」，不能把保存动作拖住） */
    private static final int WARM_QUEUE_MAX = 64;

    /**
     * 每个人最近一次真实访问用的参数（P5.92）
     *
     * <p>尺寸 / 模糊 / 主题 / 卡片底色都进内容哈希，服务端猜不出来（1600px 的窗口要 328×102，
     * 375px 的窗口要 176×82）。所以「预热」= 把这组记录下来的参数再渲一次，
     * 并且记住上次那个缓存文件路径：文件还在就跳过，不白读一遍盘也不再查库。</p>
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.List<WarmSpec>> warmSpecs = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每个人最多记几组参数：低清占位 + 正式图（换视口 / 换主题会产生新的组，旧的自动淘汰） */
    private static final int SPEC_MAX_PER_PERSON = 4;

    private final java.util.concurrent.BlockingQueue<Long> warmQueue = new java.util.concurrent.LinkedBlockingQueue<>(
            WARM_QUEUE_MAX);

    private final AtomicLong warmDone = new AtomicLong(0);

    private final AtomicLong warmSkipped = new AtomicLong(0);

    private final AtomicLong warmFailed = new AtomicLong(0);

    private final AtomicLong warmQueued = new AtomicLong(0);

    private final java.util.concurrent.ExecutorService warmPool = java.util.concurrent.Executors.newFixedThreadPool(
            WARM_THREADS, r -> {
                Thread t = new Thread(r, "qtools-cardbg-warm");
                t.setDaemon(true);
                return t;
            });

    /**
     * 参数落盘 / 读取专用（单线程）
     *
     * <p>不能和预热共用 {@link #warmPool}：那 3 个线程全被常驻消费者占着（阻塞在 take()），
     * 往同一个池里提交落盘任务会永远排在后面 —— 这正是 P5.92 第一版踩到的坑。</p>
     */
    private final java.util.concurrent.ExecutorService specPool = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qtools-cardbg-spec");
                t.setDaemon(true);
                return t;
            });

    /** 上次访问的参数 + 上次命中的缓存文件 */
    private static final class WarmSpec
    {
        private final Integer w;

        private final Integer h;

        private final BigDecimal scale;

        private final BigDecimal dx;

        private final BigDecimal dy;

        private final Integer blur;

        private final boolean dark;

        private final String bg;

        private volatile Path file;

        WarmSpec(Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy, Integer blur, boolean dark,
                String bg, Path file)
        {
            this.w = w;
            this.h = h;
            this.scale = scale;
            this.dx = dx;
            this.dy = dy;
            this.blur = blur;
            this.dark = dark;
            this.bg = bg;
            this.file = file;
        }
    }

    public MediaPersonCardBgServiceImpl()
    {
        // 常驻消费者：队列空了就阻塞，不占 CPU（不在 @PostConstruct 里起，构造即就绪，单测也好用）
        for (int i = 0; i < WARM_THREADS; i++)
        {
            warmPool.execute(this::warmLoop);
        }
    }

    /**
     * 启动后把上次落盘的参数读回来
     *
     * <p>必须挂在 {@code @PostConstruct}：构造期间 {@link #mediaProperties} 还是 null，
     * 求缓存目录会直接 NPE（P5.92 第一版就是这么静默失效的）。</p>
     */
    @jakarta.annotation.PostConstruct
    public void initWarmSpecs()
    {
        specPool.execute(this::loadSpecs);
    }

    private void warmLoop()
    {
        while (true)
        {
            Long personId;
            try
            {
                personId = warmQueue.take();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            for (WarmSpec spec : specsOf(personId))
            {
                try
                {
                    if (spec.file != null && Files.isRegularFile(spec.file) && sizeOf(spec.file) > 0)
                    {
                        warmSkipped.incrementAndGet();
                        continue;
                    }
                    cardBg(personId, spec.w, spec.h, spec.scale, spec.dx, spec.dy, spec.blur, spec.dark, spec.bg);
                    warmDone.incrementAndGet();
                }
                catch (RuntimeException e)
                {
                    warmFailed.incrementAndGet();
                    log.debug("卡片背景预热失败 person={} err={}", personId, e.getMessage());
                }
            }
        }
    }

    @Override
    public void warmAsync(java.util.Collection<Long> personIds)
    {
        if (personIds == null || personIds.isEmpty())
        {
            return;
        }
        for (Long id : personIds)
        {
            if (id == null)
            {
                continue;
            }
            // 去重：同一个人已经在队列里就不再排一次
            if (warmQueue.contains(id))
            {
                continue;
            }
            if (warmQueue.offer(id))
            {
                warmQueued.incrementAndGet();
            }
        }
    }

    @Override
    public void warmAfterEdit(java.util.Collection<Long> personIds)
    {
        if (personIds == null || personIds.isEmpty())
        {
            return;
        }
        for (Long id : personIds)
        {
            if (id == null)
            {
                continue;
            }
            java.util.List<WarmSpec> specs = specsOf(id);
            if (!specs.isEmpty())
            {
                // 缩放 / 偏移刚被改过：记录的旧值必须丢掉，让服务层用库里新存的那组
                java.util.List<WarmSpec> fresh = new java.util.ArrayList<>(specs.size());
                for (WarmSpec spec : specs)
                {
                    fresh.add(new WarmSpec(spec.w, spec.h, null, null, null, spec.blur, spec.dark, spec.bg, null));
                }
                warmSpecs.put(id, fresh);
            }
            if (!warmQueue.contains(id) && warmQueue.offer(id))
            {
                warmQueued.incrementAndGet();
            }
        }
    }

    @Override
    public CardBgImage warmOne(Long personId, Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy,
            Integer blur, boolean dark, String bgHex)
    {
        long begin = System.currentTimeMillis();
        java.util.List<WarmSpec> specs = personId == null ? java.util.List.of() : specsOf(personId);
        // 显式给了尺寸 = 只要这一张（管理端「按这个视口预演一遍」）
        if (w != null || h != null)
        {
            WarmSpec spec = specs.isEmpty() ? null : specs.get(0);
            return cardBg(personId, w, h, scale, dx, dy, blur != null ? blur : spec == null ? null : spec.blur,
                    dark || (spec != null && spec.dark), bgHex != null ? bgHex : spec == null ? null : spec.bg);
        }
        // 没给 = 把记录下来的每一组（低清 + 正式）都渲一遍；全都在就是 skipped
        boolean allHit = true;
        CardBgImage last = new CardBgImage(new byte[0], "warm-skip", true, 0, DEFAULT_W, DEFAULT_H);
        if (specs.isEmpty())
        {
            last = cardBg(personId, null, null, scale, dx, dy, blur, dark, bgHex);
            allHit = false;
        }
        for (WarmSpec spec : specs)
        {
            if (spec.file != null && Files.isRegularFile(spec.file) && sizeOf(spec.file) > 0)
            {
                continue;
            }
            allHit = false;
            last = cardBg(personId, spec.w, spec.h, spec.scale, spec.dx, spec.dy, spec.blur, spec.dark, spec.bg);
        }
        return new CardBgImage(last.getBytes(), last.getKey(), allHit, System.currentTimeMillis() - begin,
                last.getWidth(), last.getHeight());
    }

    @Override
    public java.util.Map<String, Object> warmStats()
    {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("queued", warmQueue.size());
        out.put("threads", WARM_THREADS);
        out.put("rendered", warmDone.get());
        out.put("skipped", warmSkipped.get());
        out.put("failed", warmFailed.get());
        out.put("enqueued", warmQueued.get());
        int specCount = 0;
        for (java.util.List<WarmSpec> list : warmSpecs.values())
        {
            specCount += list.size();
        }
        out.put("persons", warmSpecs.size());
        out.put("specs", specCount);
        return out;
    }

    @Autowired
    private MediaPersonMapper mediaPersonMapper;

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private IMediaFrameService mediaFrameService;

    @Autowired
    private MediaProperties mediaProperties;

    @Override
    public CardBgImage cardBg(Long personId, Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy,
            Integer blur, boolean dark, String bgHex)
    {
        long begin = System.currentTimeMillis();
        MediaPerson person = personId == null ? null : mediaPersonMapper.selectPersonDetail(personId);
        if (person == null)
        {
            throw MediaException.notFound("人物不存在: " + personId);
        }
        Source source = resolveSource(person);
        int[] dims = fitDims(w, h);
        int outW = dims[0];
        int outH = dims[1];
        double effScale = param(scale, person.getBgScale(), 0.5, 3.0, 1.0);
        double effDx = param(dx, person.getBgDx(), -2.0, 2.0, 0.0);
        double effDy = param(dy, person.getBgDy(), -2.0, 2.0, 0.0);
        int effBlur = blur == null ? 6 : Math.max(0, Math.min(20, blur));
        String cleanBg = normalizeBg(bgHex);

        String seed = "v" + RENDER_VERSION + "|" + person.getId() + "|" + source.signature + "|" + outW + "x" + outH + "|"
                + String.format(Locale.ROOT, "%.3f,%.4f,%.4f", effScale, effDx, effDy) + "|b" + effBlur + "|d"
                + (dark ? 1 : 0) + "|" + cleanBg + "|f" + MediaPersonCardBg.FADE_END + "|" + source.cropKey;
        String hash = sha1(seed);
        Path dir = cacheDir();
        Path file = dir.resolve(person.getId() + "-" + outW + "x" + outH + "-" + hash + ".jpg");
        // P5.92：记住「这个人最近一次要的是哪一组参数、落到哪个文件」—— 预热就按它来
        rememberSpec(person.getId(), w, h, scale, dx, dy, blur, dark, bgHex, file);

        if (Files.isRegularFile(file) && sizeOf(file) > 0)
        {
            try
            {
                byte[] bytes = Files.readAllBytes(file);
                touchIfStale(file);
                return new CardBgImage(bytes, hash, true, System.currentTimeMillis() - begin, outW, outH);
            }
            catch (IOException e)
            {
                log.warn("读卡片背景缓存失败，改为重渲染 file={} err={}", file, e.getMessage());
            }
        }

        BufferedImage image = renderImage(source, outW, outH, effScale, effDx, effDy, effBlur, cleanBg, dark);
        byte[] bytes;
        try
        {
            bytes = MediaPersonCardBg.encodeJpeg(image, QUALITY);
            writeAtomically(file, bytes);
        }
        catch (IOException e)
        {
            log.error("卡片背景渲染落盘失败 person={} w={} h={} err={}", person.getId(), outW, outH, e.getMessage());
            throw MediaException.serviceUnavailable("卡片背景渲染失败: " + e.getMessage());
        }
        purgeIfDue(dir);
        long cost = System.currentTimeMillis() - begin;
        log.info("预渲染人物卡背景 person={} {}x{} scale={} dx={} dy={} blur={} dark={} key={} bytes={} cost={}ms",
                person.getId(), outW, outH, effScale, effDx, effDy, effBlur, dark, hash, bytes.length, cost);
        return new CardBgImage(bytes, hash, false, cost, outW, outH);
    }

    /** 某个人的全部历史参数（最近的在前；没有就空表） */
    private java.util.List<WarmSpec> specsOf(Long personId)
    {
        java.util.List<WarmSpec> list = personId == null ? null : warmSpecs.get(personId);
        return list == null ? java.util.List.of() : list;
    }

    /**
     * 记下这个人这次要的参数与落到的缓存文件（去重、最近优先、每人最多 {@link #SPEC_MAX_PER_PERSON} 组）
     *
     * <p>为什么要留多组而不是只留最后一组：首屏每张卡有两个 URL —— 低清占位（240×…）和正式图（328×…）。
     * 只记最后一组，预热就只能热正式图，冷缓存访问仍然是 13 HIT + 13 MISS。</p>
     */
    private void rememberSpec(Long personId, Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy,
            Integer blur, boolean dark, String bgHex, Path file)
    {
        java.util.List<WarmSpec> prev = warmSpecs.computeIfAbsent(personId,
                k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>(SPEC_MAX_PER_PERSON + 1)));
        synchronized (prev)
        {
            for (int i = 0; i < prev.size(); i++)
            {
                WarmSpec s = prev.get(i);
                if (java.util.Objects.equals(s.w, w) && java.util.Objects.equals(s.h, h)
                        && java.util.Objects.equals(s.blur, blur) && s.dark == dark
                        && java.util.Objects.equals(s.bg, bgHex) && java.util.Objects.equals(s.scale, scale)
                        && java.util.Objects.equals(s.dx, dx) && java.util.Objects.equals(s.dy, dy))
                {
                    if (file != null && !file.equals(s.file))
                    {
                        s.file = file;
                    }
                    if (i > 0)
                    {
                        prev.remove(i);
                        prev.add(0, s);
                    }
                    return;
                }
            }
            prev.add(0, new WarmSpec(w, h, scale, dx, dy, blur, dark, bgHex, file));
            while (prev.size() > SPEC_MAX_PER_PERSON)
            {
                prev.remove(prev.size() - 1);
            }
        }
        scheduleSpecPersist();
    }

    /* ------------------------------------------------------- 预热参数持久化（P5.92） */

    /**
     * 参数落盘：{@code <thumbs>/cardbg/warm-spec.json}
     *
     * <p>为什么必须落盘：这些参数是「这个人上一次访问要的是多大、多糊、什么主题」，
     * 进程重启后如果只剩内存里的，就退化成「缺省 666×204 亮色」—— 而真实浏览器要的是
     * 328×102 暗色，一个都对不上，预热等于白跑。落盘之后「部署 / 重启 / 清过缓存」这些场景
     * 才能靠上一批真实访问的参数把图渲好。</p>
     */
    private static final String SPEC_FILE = "warm-spec.json";

    private final java.util.concurrent.atomic.AtomicBoolean specDirty = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    private final AtomicLong lastSpecSave = new AtomicLong(0);

    private void scheduleSpecPersist()
    {
        specDirty.set(true);
        long now = System.currentTimeMillis();
        long last = lastSpecSave.get();
        // 2 秒最多写一次：13 张卡一轮访问会连续改 26 次，没必要写 26 遍盘
        if (now - last < 2000 || !lastSpecSave.compareAndSet(last, now))
        {
            return;
        }
        final boolean save = specDirty.compareAndSet(true, false);
        if (!save)
        {
            return;
        }
        specPool.execute(this::persistSpecs);
    }

    private void persistSpecs()
    {
        Path dir;
        try
        {
            dir = cacheDir();
        }
        catch (RuntimeException e)
        {
            return;
        }
        // 用 Jackson 拼：手写 JSON 的引号转义在 Java 里既难读又容易写错
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, java.util.List<WarmSpec>> e : warmSpecs.entrySet())
        {
            if (e.getValue() == null || e.getValue().isEmpty())
            {
                continue;
            }
            java.util.List<Object> nodes = new java.util.ArrayList<>();
            for (WarmSpec s : new java.util.ArrayList<>(e.getValue()))
            {
                Map<String, Object> node = new java.util.LinkedHashMap<>();
                node.put("w", s.w);
                node.put("h", s.h);
                node.put("s", s.scale == null ? null : s.scale.toPlainString());
                node.put("dx", s.dx == null ? null : s.dx.toPlainString());
                node.put("dy", s.dy == null ? null : s.dy.toPlainString());
                node.put("blur", s.blur);
                node.put("d", s.dark ? 1 : 0);
                node.put("bg", s.bg);
                nodes.add(node);
            }
            out.put(String.valueOf(e.getKey()), nodes);
        }
        String json;
        try
        {
            json = mapper.writeValueAsString(out);
        }
        catch (Exception ex)
        {
            log.debug("预热参数序列化失败（忽略）: {}", ex.getMessage());
            return;
        }
        try
        {
            Path tmp = Files.createTempFile(dir, "spec", ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Path target = dir.resolve(SPEC_FILE);
            try
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicFailed)
            {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            log.debug("预热参数落盘失败（不影响渲染）: {}", e.getMessage());
        }
    }

    /** 启动时把上次落盘的参数读回来（失败就当作没有：预热退化成缺省尺寸，绝不抛异常） */
    private void loadSpecs()
    {
        try
        {
            Path file = cacheDir().resolve(SPEC_FILE);
            if (!Files.isRegularFile(file))
            {
                return;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(text);
            boolean first = true;
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = root
                    .fields();
            while (it.hasNext())
            {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
                Long id;
                try
                {
                    id = Long.valueOf(e.getKey());
                }
                catch (NumberFormatException ignored)
                {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode n = e.getValue();
                java.util.List<WarmSpec> list = java.util.Collections
                        .synchronizedList(new java.util.ArrayList<>(SPEC_MAX_PER_PERSON + 1));
                for (com.fasterxml.jackson.databind.JsonNode node : n)
                {
                    list.add(new WarmSpec(
                            node.hasNonNull("w") ? Integer.valueOf(node.get("w").asInt()) : null,
                            node.hasNonNull("h") ? Integer.valueOf(node.get("h").asInt()) : null,
                            node.hasNonNull("s") ? new BigDecimal(node.get("s").asText()) : null,
                            node.hasNonNull("dx") ? new BigDecimal(node.get("dx").asText()) : null,
                            node.hasNonNull("dy") ? new BigDecimal(node.get("dy").asText()) : null,
                            node.hasNonNull("blur") ? Integer.valueOf(node.get("blur").asInt()) : null,
                            node.has("d") && node.get("d").asInt() == 1,
                            node.hasNonNull("bg") ? node.get("bg").asText() : null, null));
                }
                if (!list.isEmpty())
                {
                    warmSpecs.put(id, list);
                }
                first = false;
            }
            if (!first)
            {
                log.info("卡片背景预热参数已恢复 persons={}", warmSpecs.size());
            }
        }
        catch (Exception e)
        {
            log.debug("冷却参数读取失败（忽略）: {}", e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ 渲染 */

    private BufferedImage renderImage(Source source, int w, int h, double scale, double dx, double dy, int blur,
            String bgHex, boolean dark)
    {
        MediaPersonCardBg.Geometry g = MediaPersonCardBg.geometry(source.image.getWidth(),
                source.image.getHeight(), w, h, source.cropX, source.cropY, source.cropW, source.cropH, scale, dx,
                dy);
        if (g == null)
        {
            throw MediaException.notFound("该人物头像原图不可用");
        }
        int[] themeBg = hexToRgb(bgHex);
        return MediaPersonCardBg.render(source.image, g, w, h, blur, themeBg, dark);
    }

    /* ------------------------------------------------------------------ 头像来源 */

    /** 渲染源：整图 + 缓存签名 + 用于几何的裁剪框（比例） */
    private static final class Source
    {
        private final BufferedImage image;

        private final String signature;

        private final String cropKey;

        private final double cropX;

        private final double cropY;

        private final double cropW;

        private final double cropH;

        Source(BufferedImage image, String signature, String cropKey, double cropX, double cropY, double cropW,
                double cropH)
        {
            this.image = image;
            this.signature = signature;
            this.cropKey = cropKey;
            this.cropX = cropX;
            this.cropY = cropY;
            this.cropW = cropW;
            this.cropH = cropH;
        }
    }

    /**
     * 解析人物头像来源
     *
     * <p>与前端 {@code bgSrcOf} / {@code originalSrc} 同口径：背景用<b>整张原图</b>，ref 尾巴上的
     * {@code #crop=} 只参与几何（裁剪中心），不参与取图。头像来源只支持 photo（相册缩略图）与
     * frame（视频取帧）两档 —— {@code url} 档是外部地址，服务端不去抓（SSRF 面），交给前端三层兜底。</p>
     */
    private Source resolveSource(MediaPerson person)
    {
        String kind = trim(person.getAvatarKind()).toLowerCase(Locale.ROOT);
        String ref = trim(person.getAvatarRef());
        double[] crop = parseCrop(ref);
        if (ref.isEmpty())
        {
            throw MediaException.notFound("该人物没有手动头像，无法预渲染背景");
        }
        String body = ref;
        int hash = ref.indexOf('#');
        if (hash >= 0)
        {
            body = ref.substring(0, hash).trim();
        }
        if ("photo".equals(kind))
        {
            int colon = body.indexOf(':');
            if (colon <= 0)
            {
                throw MediaException.notFound("头像来源不完整: " + ref);
            }
            String albumId = body.substring(0, colon).trim();
            String seqText = body.substring(colon + 1).trim();
            Path path = mediaFileService.resolveThumbPhoto(albumId, seqText, false);
            try
            {
                BufferedImage image = ImageIO.read(path.toFile());
                if (image == null)
                {
                    throw MediaException.notFound("头像缩略图不可解码: " + path);
                }
                String sig = "photo|" + path.toAbsolutePath() + "|" + Files.size(path) + "|"
                        + Files.getLastModifiedTime(path).toMillis();
                return new Source(image, sig, ref, crop[0], crop[1], crop[2], crop[3]);
            }
            catch (IOException e)
            {
                throw MediaException.notFound("头像缩略图读取失败: " + e.getMessage());
            }
        }
        if ("frame".equals(kind))
        {
            // frame:<videoId>:<秒>
            String[] parts = body.split(":");
            if (parts.length < 3)
            {
                throw MediaException.notFound("头像来源不完整: " + ref);
            }
            String videoId = parts[1].trim();
            String secText = parts[2].trim();
            byte[] bytes;
            String sig;
            try
            {
                double sec = Double.parseDouble(secText);
                if (sec == Math.rint(sec))
                {
                    bytes = mediaFrameService.frame(videoId, (int) sec);
                    sig = "frame|" + videoId + "|" + (int) sec;
                }
                else
                {
                    bytes = mediaFrameService.frameAt(videoId, sec);
                    sig = "frame|" + videoId + "|" + sec;
                }
            }
            catch (NumberFormatException e)
            {
                throw MediaException.notFound("头像取帧秒数非法: " + ref);
            }
            try
            {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null)
                {
                    throw MediaException.notFound("头像取帧结果不可解码");
                }
                return new Source(image, sig + "|" + bytes.length, ref, crop[0], crop[1], crop[2], crop[3]);
            }
            catch (IOException e)
            {
                throw MediaException.notFound("头像取帧结果读取失败: " + e.getMessage());
            }
        }
        throw MediaException.notFound("该头像来源不支持服务端预渲染（仅 photo / frame）: kind=" + kind);
    }

    /** {@code #crop=x,y,w,h} → 四个比例；没有 / 脏值 = 整图 */
    private static double[] parseCrop(String ref)
    {
        String tail = MediaAvatarCrop.tailOf(ref);
        if (tail != null)
        {
            try
            {
                String norm = MediaAvatarCrop.normalize(tail);
                if (norm != null)
                {
                    String[] parts = norm.split(",");
                    if (parts.length == 4)
                    {
                        return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[3]) };
                    }
                }
            }
            catch (IllegalArgumentException ignored)
            {
                // 脏裁剪参数 → 整图（与前端 parseCrop 同口径：背景不因为裁剪坏了就不显示）
            }
        }
        return new double[] { 0, 0, 1, 1 };
    }

    /* ------------------------------------------------------------------ 缓存 */

    private Path cacheDir()
    {
        String thumbs = mediaProperties == null ? null : trim(mediaProperties.getThumbsPath());
        if (thumbs.isEmpty())
        {
            throw MediaException.serviceUnavailable("qtools.media.thumbsPath 未配置，卡片背景无法缓存");
        }
        Path dir = Path.of(thumbs, CACHE_DIR);
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException e)
        {
            throw MediaException.serviceUnavailable("卡片背景缓存目录不可创建: " + dir);
        }
        return dir;
    }

    private void writeAtomically(Path file, byte[] bytes) throws IOException
    {
        Path tmp = Files.createTempFile(file.getParent(), "cbg", ".tmp");
        try
        {
            Files.write(tmp, bytes);
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicFailed)
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(tmp);
        }
    }

    private static long sizeOf(Path file)
    {
        try
        {
            return Files.size(file);
        }
        catch (IOException e)
        {
            return -1;
        }
    }

    private static void touchIfStale(Path file)
    {
        try
        {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (System.currentTimeMillis() - mtime > TOUCH_AFTER_MS)
            {
                Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
        }
        catch (IOException ignored)
        {
            // touch 失败不影响命中：最坏情况是这条缓存被提前清掉，下个请求重渲染一张
        }
    }

    /** 删掉 30 天没被访问的缓存文件（6 小时最多一次；失败只记日志） */
    private void purgeIfDue(Path dir)
    {
        long now = System.currentTimeMillis();
        long last = LAST_PURGE.get();
        if (now - last < PURGE_INTERVAL_MS || !LAST_PURGE.compareAndSet(last, now))
        {
            return;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir))
        {
            for (Path p : (Iterable<Path>) files::iterator)
            {
                try
                {
                    if (Files.isRegularFile(p) && now - Files.getLastModifiedTime(p).toMillis() > CACHE_TTL_MS)
                    {
                        Files.deleteIfExists(p);
                        removed++;
                    }
                }
                catch (IOException ignored)
                {
                    // 单个文件删不掉就跳过
                }
            }
        }
        catch (IOException e)
        {
            log.warn("卡片背景缓存清理失败 dir={} err={}", dir, e.getMessage());
            return;
        }
        if (removed > 0)
        {
            log.info("卡片背景缓存清理完成 dir={} removed={} (30 天未访问)", dir, removed);
        }
    }

    /* ------------------------------------------------------------------ 工具 */

    /**
     * 输出尺寸：缺省 666×204；超出 1200×600 时<b>按比例</b>缩（不裁、不拉伸），并取偶数
     *
     * <p>取偶数是为了挡住 DPR 四舍五入的 1px 抖动：尺寸只差 1px 会变成一个新的缓存文件，
     * 而视觉上完全看不出差别。</p>
     */
    static int[] fitDims(Integer w, Integer h)
    {
        int vw = Math.max(2, Math.min(2000, w == null ? DEFAULT_W : w));
        int vh = Math.max(2, Math.min(2000, h == null ? DEFAULT_H : h));
        double factor = Math.min(1.0, Math.min((double) MAX_W / vw, (double) MAX_H / vh));
        int ow = even(Math.max(2, (int) Math.round(vw * factor)));
        int oh = even(Math.max(2, (int) Math.round(vh * factor)));
        return new int[] { ow, oh };
    }

    private static int even(int value)
    {
        return value % 2 == 0 ? value : value - 1;
    }

    /** 有效参数：请求参数（夹到值域） &gt; 人物已保存值 &gt; 默认 */
    private static double param(BigDecimal fromRequest, BigDecimal saved, double min, double max, double fallback)
    {
        BigDecimal raw = fromRequest != null ? fromRequest : saved;
        if (raw == null)
        {
            return fallback;
        }
        double v = raw.setScale(4, RoundingMode.HALF_UP).doubleValue();
        return Math.max(min, Math.min(max, v));
    }

    private static String normalizeBg(String bgHex)
    {
        String s = trim(bgHex).toLowerCase(Locale.ROOT);
        if (s.startsWith("#"))
        {
            s = s.substring(1);
        }
        return s.matches("^[0-9a-f]{6}$") ? s : "ffffff";
    }

    private static int[] hexToRgb(String hex)
    {
        return new int[] { Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16) };
    }

    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }

    private static String sha1(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.substring(0, 20);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
