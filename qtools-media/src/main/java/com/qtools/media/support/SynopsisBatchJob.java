package com.qtools.media.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量生成简介的**单个任务**（P5.47 交付 2 的「逐个处理」；P5.55 加协作式取消 + 旧值快照）
 *
 * <p>为什么不是「先全部生成再一起落库」：用户的原话是「生成一个替换一个」——每条处理完立刻
 * {@link Ops#persist}，任务中途被打断 / 前端刷新 / 后端重启时，已经完成的那几条**是真的落库了**，
 * 不会因为最后一条超时把前面 100 条的成果一起回滚。</p>
 *
 * <h2>P5.55：协作式取消（stop）</h2>
 *
 * <p>{@link #requestCancel()} 只置一个标志，<b>不打断正在生成的那一条</b>：执行循环每处理完一条检查该
 * 标志，为真就把 status 置成 {@link #JOB_STOPPED} 并跳出。语义是「正在生成的这条算数，后面的不再开始」，
 * 因为那一条的模型调用已经花掉了时间，中途掐断只会留下半截状态。</p>
 *
 * <p>幂等：对 done / stopped / failed 的任务再 cancel 一次，只把标志置上、返回当前状态，不抛异常
 * —— 前端点两下「停止」不该报错。</p>
 *
 * <h2>P5.55：旧值快照（可撤销）</h2>
 *
 * <p>每条真正落库前把<b>旧值</b>记进 {@link #getPrevious()}（空也是记录，用空串表示「原来是空的」）。
 * 于是 {@code canRollback=true} 的任务可以「撤销本次批量」：旧值为空 → 清空，非空 → 写回原文。</p>
 *
 * <p>线程模型：本类本身不建线程池，由调用方（{@code MediaSynopsisServiceImpl}）用单线程执行器
 * {@link #run(Ops)}，因此多个任务天然串行、不会同时压本地模型；进度字段用 volatile，
 * 查询线程能在处理中途读到「已处理 N / 共 M」。</p>
 *
 * <p>纯逻辑（只有 JDK 依赖），夹具可以直接 new 一个跑：{@code overwrite=false} 时跳过的判定、
 * 取消时机、逐条计数、失败不中断，全部在这里被测。</p>
 *
 * @author qtools
 */
public final class SynopsisBatchJob
{
    /** 单条状态 */
    public static final String ITEM_GENERATED = "generated";

    public static final String ITEM_SKIPPED = "skipped";

    public static final String ITEM_FAILED = "failed";

    /** 任务状态：pending（已入队）→ running → done / stopped / failed */
    public static final String JOB_PENDING = "pending";

    public static final String JOB_RUNNING = "running";

    public static final String JOB_DONE = "done";

    /** 手动停止（P5.55）：已处理的那几条已落库，剩余不再开始 */
    public static final String JOB_STOPPED = "stopped";

    /** 任务级失败（例如执行器拒绝 / 未知异常）：单条失败只记到 items 上，任务继续 */
    public static final String JOB_FAILED = "failed";

    /** 跳过原因分类（P5.55）：已有内容，overwrite=false 跳过 */
    public static final String SKIP_EXISTING = "existing";

    /** 跳过原因分类（P5.55）：没有可概括的素材（无字幕 + 无画面）→ insufficient */
    public static final String SKIP_INSUFFICIENT = "insufficient";

    /** 跳过原因分类（P5.55）：其它（后续新增的跳过理由默认落这里） */
    public static final String SKIP_OTHER = "other";

    /** 单条错误文案截断长度 */
    private static final int ERROR_CHARS = 200;

    private final String id;

    /** template | llm */
    private final String mode;

    /** false = 已有内容就跳过（默认，也是前端默认值） */
    private final boolean overwrite;

    private final List<String> videoIds;

    private final List<Map<String, Object>> items = Collections.synchronizedList(new ArrayList<>());

    /**
     * 旧值快照：videoId → 落库前的简介原文（空串 = 原来是空的）
     *
     * <p>只记真正 generated 的条目 —— 没动过的字段不需要撤销。</p>
     */
    private final Map<String, String> previous = Collections.synchronizedMap(new LinkedHashMap<String, String>());

    /** 跳过原因分类计数：existing / insufficient / other */
    private final Map<String, Integer> skipReasons = Collections.synchronizedMap(new LinkedHashMap<String, Integer>());

    private final long createdAt = System.currentTimeMillis();

    private volatile String status = JOB_PENDING;

    private volatile String error;

    /** 停止标志（P5.55）：true 后执行循环在处理完当前这条后跳出 */
    private volatile boolean cancelRequested;

    private volatile int processed;

    private volatile int generated;

    private volatile int skipped;

    private volatile int failed;

    private volatile long startedAt;

    private volatile long stoppedAt;

    private volatile long finishedAt;

    public SynopsisBatchJob(String id, String mode, boolean overwrite, List<String> videoIds)
    {
        this.id = id;
        this.mode = mode;
        this.overwrite = overwrite;
        this.videoIds = videoIds == null ? new ArrayList<>() : new ArrayList<>(videoIds);
    }

    public String getId()
    {
        return id;
    }

    public String getMode()
    {
        return mode;
    }

    public boolean isOverwrite()
    {
        return overwrite;
    }

    /** 是否已请求停止（前端据此显示「正在停止…（等本条生成完）」） */
    public boolean isCancelRequested()
    {
        return cancelRequested;
    }

    /** 任务是否已经在跑（含 pending：pending 也算「有活儿没干完」） */
    public boolean isActive()
    {
        String now = status;
        return JOB_PENDING.equals(now) || JOB_RUNNING.equals(now);
    }

    /** 请求停止：幂等，只置标志，不打断正在生成的那一条 */
    public void requestCancel()
    {
        cancelRequested = true;
    }

    /** 旧值快照（videoId → 落库前原文，空串 = 原来是空的）；不可撤销时为空表 */
    public Map<String, String> getPrevious()
    {
        synchronized (previous)
        {
            return new LinkedHashMap<>(previous);
        }
    }

    /** 能不能撤销：至少有一条记下了旧值 */
    public boolean canRollback()
    {
        return !previous.isEmpty();
    }

    /**
     * 单条处理所需的外部动作（DB / 模型都由调用方注入，本类不碰 Spring）
     */
    public interface Ops
    {
        /** 标题（只用于回包，取不到就 null） */
        String title(String videoId);

        /** 库里已有的简介（null / 空串 = 没有） */
        String existing(String videoId);

        /**
         * 这一条为什么不能生成（null = 可以生成）
         *
         * <p>P5.53：简介要先有内容素材（字幕 / 画面）。没有素材的片子不该算 failed
         * —— 它没有出错，只是没东西可概括。返回非空文案时这条计为 skipped 并带上原因。</p>
         */
        default String skipReason(String videoId)
        {
            return null;
        }

        /**
         * 跳过原因分类（P5.55）：{@link #SKIP_INSUFFICIENT} / {@link #SKIP_OTHER}
         *
         * <p>只在 {@link #skipReason} 非空时被问；「已有内容」这一类由本类自己记，不问调用方。</p>
         */
        default String skipCode(String videoId)
        {
            return SKIP_OTHER;
        }

        /** 生成一条正文（模板或模型）；抛异常 = 这一条 failed，不影响后续 */
        String generate(String videoId) throws Exception;

        /**
         * 「生成结果为空」该记成哪一类（P5.55）
         *
         * <p>模板模式下空结果 = 真失败（结构化信息全空，模板渲染不出来）；
         * 但模型模式下空结果是<b>素材不足的正常结论</b>（insufficient：没字幕也没有可概括的画面），
         * 计成 failed 会让用户以为「程序出错了」。默认按失败处理（{@link #SKIP_OTHER}）。</p>
         */
        default String emptyCode(String videoId)
        {
            return SKIP_OTHER;
        }

        /** 落库一条（每条处理完立即调用，一个完成一个替换） */
        void persist(String videoId, String text);
    }

    /**
     * 跑完整个任务（同步；调用方在用单线程执行器，所以「逐个」是调度层面的保证）
     *
     * <p>P5.55：每条处理完检查一次 {@link #cancelRequested}；为真则 status=stopped 并跳出。</p>
     */
    public void run(Ops ops)
    {
        status = JOB_RUNNING;
        startedAt = System.currentTimeMillis();
        try
        {
            for (String videoId : videoIds)
            {
                if (cancelRequested)
                {
                    break;
                }
                processOne(ops, videoId);
            }
            if (cancelRequested)
            {
                status = JOB_STOPPED;
                stoppedAt = System.currentTimeMillis();
            }
            else
            {
                status = JOB_DONE;
            }
        }
        catch (RuntimeException e)
        {
            // 只会是 Ops 之外的问题（例如标题查询把连接耗尽）；单条异常已在 processOne 里吃掉
            status = JOB_FAILED;
            error = brief(e.getMessage());
        }
        finally
        {
            finishedAt = System.currentTimeMillis();
        }
    }

    private void processOne(Ops ops, String videoId)
    {
        String title = null;
        try
        {
            title = ops.title(videoId);
            String existing = ops.existing(videoId);
            boolean hasExisting = existing != null && !existing.trim().isEmpty();
            if (!overwrite && hasExisting)
            {
                skipped++;
                bumpSkipReason(SKIP_EXISTING);
                addItem(videoId, title, ITEM_SKIPPED, SynopsisText.codePoints(existing), 0, "已有内容，overwrite=false 跳过",
                        SKIP_EXISTING);
                return;
            }
            String skip = ops.skipReason(videoId);
            if (skip != null && !skip.trim().isEmpty())
            {
                String code = normalizeSkipCode(ops.skipCode(videoId));
                skipped++;
                bumpSkipReason(code);
                addItem(videoId, title, ITEM_SKIPPED, 0, 0, skip, code);
                return;
            }
            long begin = System.currentTimeMillis();
            String text = ops.generate(videoId);
            long ms = System.currentTimeMillis() - begin;
            if (text == null || text.trim().isEmpty())
            {
                String emptyCode = normalizeSkipCode(ops.emptyCode(videoId));
                if (SKIP_OTHER.equals(emptyCode))
                {
                    failed++;
                    addItem(videoId, title, ITEM_FAILED, 0, ms, "生成结果为空", null);
                }
                else
                {
                    // 素材不足不是错误：算跳过，并归到 skipReasons 里单独报数
                    skipped++;
                    bumpSkipReason(emptyCode);
                    addItem(videoId, title, ITEM_SKIPPED, 0, ms, "没有可概括的内容素材（生成结果为空）", emptyCode);
                }
                return;
            }
            // 先记旧值再落库：中途被打断也不会出现「改过了但没有旧值」的行
            previous.put(videoId, hasExisting ? existing : "");
            ops.persist(videoId, text);
            generated++;
            addItem(videoId, title, ITEM_GENERATED, SynopsisText.codePoints(text), ms, null, null);
        }
        catch (Exception e)
        {
            failed++;
            addItem(videoId, title, ITEM_FAILED, 0, 0, brief(e.getMessage()), null);
        }
        finally
        {
            processed++;
        }
    }

    private static String normalizeSkipCode(String raw)
    {
        String code = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (SKIP_EXISTING.equals(code) || SKIP_INSUFFICIENT.equals(code) || SKIP_OTHER.equals(code))
        {
            return code;
        }
        return SKIP_OTHER;
    }

    private void bumpSkipReason(String code)
    {
        synchronized (skipReasons)
        {
            Integer old = skipReasons.get(code);
            skipReasons.put(code, old == null ? 1 : old + 1);
        }
    }

    private void addItem(String videoId, String title, String itemStatus, int chars, long ms, String message,
            String skipCode)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("videoId", videoId);
        item.put("title", title);
        item.put("status", itemStatus);
        item.put("chars", chars);
        item.put("ms", ms);
        if (skipCode != null)
        {
            item.put("skipCode", skipCode);
        }
        if (message != null)
        {
            item.put("message", message);
        }
        items.add(item);
    }

    /** 剩余未处理条数（停止后就是「没来得及处理的那些」） */
    private int remaining()
    {
        int left = videoIds.size() - processed;
        return left < 0 ? 0 : left;
    }

    /** 回包（进度查询与创建时返回的是同一份结构） */
    public Map<String, Object> toNode()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", id);
        out.put("mode", mode);
        out.put("overwrite", overwrite);
        out.put("status", status);
        out.put("cancelRequested", cancelRequested);
        out.put("total", videoIds.size());
        out.put("processed", processed);
        out.put("generated", generated);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("createdAt", createdAt);
        out.put("startedAt", startedAt);
        out.put("stoppedAt", stoppedAt);
        out.put("finishedAt", finishedAt);
        // P5.55：停止按钮与成果汇报要的「一份就够」的计数（前端不再自己加减）
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", videoIds.size());
        counts.put("processed", processed);
        counts.put("generated", generated);
        counts.put("skipped", skipped);
        counts.put("failed", failed);
        counts.put("remaining", remaining());
        out.put("counts", counts);
        Map<String, Object> reasons = new LinkedHashMap<>();
        synchronized (skipReasons)
        {
            reasons.put(SKIP_EXISTING, skipReasons.getOrDefault(SKIP_EXISTING, 0));
            reasons.put(SKIP_INSUFFICIENT, skipReasons.getOrDefault(SKIP_INSUFFICIENT, 0));
            reasons.put(SKIP_OTHER, skipReasons.getOrDefault(SKIP_OTHER, 0));
        }
        out.put("skipReasons", reasons);
        out.put("canRollback", canRollback());
        out.put("rollbackCount", previous.size());
        if (error != null)
        {
            out.put("error", error);
        }
        synchronized (items)
        {
            out.put("items", new ArrayList<>(items));
        }
        return out;
    }

    private static String brief(String message)
    {
        if (message == null)
        {
            return "未知错误";
        }
        String text = message.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= ERROR_CHARS ? text : text.substring(0, ERROR_CHARS) + "…";
    }
}
