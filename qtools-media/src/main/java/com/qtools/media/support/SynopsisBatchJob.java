package com.qtools.media.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量生成简介的**单个任务**（P5.47 交付 2 的「逐个处理」）
 *
 * <p>为什么不是「先全部生成再一起落库」：用户的原话是「生成一个替换一个」——每条处理完立刻
 * {@link Ops#persist}，任务中途被打断 / 前端刷新 / 后端重启时，已经完成的那几条**是真的落库了**，
 * 不会因为最后一条超时把前面 100 条的成果一起回滚。</p>
 *
 * <p>线程模型：本类本身不建线程池，由调用方（{@code MediaSynopsisServiceImpl}）用单线程执行器
 * {@link #run(Ops)}，因此多个任务天然串行、不会同时压本地模型；进度字段用 volatile，
 * 查询线程能在处理中途读到「已处理 N / 共 M」。</p>
 *
 * <p>纯逻辑（只有 JDK 依赖），夹具可以直接 new 一个跑：{@code overwrite=false} 时跳过的判定、
 * 逐条计数、失败不中断，全部在这里被测。</p>
 *
 * @author qtools
 */
public final class SynopsisBatchJob
{
    /** 单条状态 */
    public static final String ITEM_GENERATED = "generated";

    public static final String ITEM_SKIPPED = "skipped";

    public static final String ITEM_FAILED = "failed";

    /** 任务状态：pending（已入队）→ running → done / failed */
    public static final String JOB_PENDING = "pending";

    public static final String JOB_RUNNING = "running";

    public static final String JOB_DONE = "done";

    /** 任务级失败（例如执行器拒绝 / 未知异常）：单条失败只记到 items 上，任务继续 */
    public static final String JOB_FAILED = "failed";

    /** 单条错误文案截断长度 */
    private static final int ERROR_CHARS = 200;

    private final String id;

    /** template | llm */
    private final String mode;

    /** false = 已有内容就跳过（默认） */
    private final boolean overwrite;

    private final List<String> videoIds;

    private final List<Map<String, Object>> items = Collections.synchronizedList(new ArrayList<>());

    private final long createdAt = System.currentTimeMillis();

    private volatile String status = JOB_PENDING;

    private volatile String error;

    private volatile int processed;

    private volatile int generated;

    private volatile int skipped;

    private volatile int failed;

    private volatile long startedAt;

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

        /** 生成一条正文（模板或模型）；抛异常 = 这一条 failed，不影响后续 */
        String generate(String videoId) throws Exception;

        /** 落库一条（每条处理完立即调用，一个完成一个替换） */
        void persist(String videoId, String text);
    }

    /**
     * 跑完整个任务（同步；调用方在用单线程执行器，所以「逐个」是调度层面的保证）
     */
    public void run(Ops ops)
    {
        status = JOB_RUNNING;
        startedAt = System.currentTimeMillis();
        try
        {
            for (String videoId : videoIds)
            {
                processOne(ops, videoId);
            }
            status = JOB_DONE;
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
            if (!overwrite)
            {
                String existing = ops.existing(videoId);
                if (existing != null && !existing.trim().isEmpty())
                {
                    skipped++;
                    addItem(videoId, title, ITEM_SKIPPED, SynopsisText.codePoints(existing), 0, "已有内容，overwrite=false 跳过");
                    return;
                }
            }
            String skip = ops.skipReason(videoId);
            if (skip != null && !skip.trim().isEmpty())
            {
                skipped++;
                addItem(videoId, title, ITEM_SKIPPED, 0, 0, skip);
                return;
            }
            long begin = System.currentTimeMillis();
            String text = ops.generate(videoId);
            long ms = System.currentTimeMillis() - begin;
            if (text == null || text.trim().isEmpty())
            {
                failed++;
                addItem(videoId, title, ITEM_FAILED, 0, ms, "生成结果为空");
                return;
            }
            ops.persist(videoId, text);
            generated++;
            addItem(videoId, title, ITEM_GENERATED, SynopsisText.codePoints(text), ms, null);
        }
        catch (Exception e)
        {
            failed++;
            addItem(videoId, title, ITEM_FAILED, 0, 0, brief(e.getMessage()));
        }
        finally
        {
            processed++;
        }
    }

    private void addItem(String videoId, String title, String itemStatus, int chars, long ms, String message)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("videoId", videoId);
        item.put("title", title);
        item.put("status", itemStatus);
        item.put("chars", chars);
        item.put("ms", ms);
        if (message != null)
        {
            item.put("message", message);
        }
        items.add(item);
    }

    /** 回包（进度查询与创建时返回的是同一份结构） */
    public Map<String, Object> toNode()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", id);
        out.put("mode", mode);
        out.put("overwrite", overwrite);
        out.put("status", status);
        out.put("total", videoIds.size());
        out.put("processed", processed);
        out.put("generated", generated);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("createdAt", createdAt);
        out.put("startedAt", startedAt);
        out.put("finishedAt", finishedAt);
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
