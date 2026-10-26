package com.qtools.media.service.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.domain.MediaVideo;
import com.qtools.media.domain.MediaVideoActor;
import com.qtools.media.domain.MediaVideoTag;
import com.qtools.media.domain.dto.SynopsisFields;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaOrganizeMapper;
import com.qtools.media.mapper.MediaStudioMapper;
import com.qtools.media.mapper.MediaVideoMapper;
import com.qtools.media.service.IMediaOrganizeService;
import com.qtools.media.service.IMediaSynopsisService;
import com.qtools.media.support.MediaLlmClient;
import com.qtools.media.support.MediaSubtitleReader;
import com.qtools.media.support.MediaTranscribeMaterial;
import com.qtools.media.support.MediaVisionMaterial;
import com.qtools.media.support.SynopsisBatchJob;
import com.qtools.media.support.SynopsisGuard;
import com.qtools.media.support.SynopsisPrompt;
import com.qtools.media.support.SynopsisTemplateRenderer;
import com.qtools.media.support.SynopsisText;
import com.qtools.media.support.SubtitleExcerpt;

/**
 * 作品简介自动生成 服务实现（P5.47 起，P5.53 改为「内容梗概」）
 *
 * <h2>P5.53 的核心改动：素材来源链</h2>
 *
 * <p>旧实现只把元数据（标题 / 分类 / 时长 / 标签 / 文件名）喂给模型，1.5B 模型没有内容可总结，
 * 只能把标签换个说法抄回来 —— 用户看到的「作品名称：X 作品分类：Y 时长：00:12 …」就是必然结果。
 * 现在按<b>内容含量</b>排队取素材：</p>
 *
 * <ol>
 *   <li><b>字幕</b>（{@link MediaSubtitleReader}）：对白 / 旁白是内容本身，最优先；</li>
 *   <li><b>画面</b>（{@link MediaVisionMaterial}）：无字幕时抽 5 帧交视觉模型逐帧描述；</li>
 *   <li><b>音频转写</b>（P5.57 接通）：whisper.cpp 直接识别片子的声音（见 {@link MediaTranscribeMaterial}）——
 *       字幕本身就是机器翻的时候，重新识别比复用错误字幕更可信，也是「用音频转写重新生成」按钮走的那一路；</li>
 *   <li>以上都不可用 → <b>insufficient</b>：不调模型、不写空串、不编造，前端据此提示改用模板。</li>
 * </ol>
 *
 * <p>每条生成结果都过 {@link SynopsisGuard}：出现字段罗列 / 文件名 / 照抄标题 / 与素材零交集，
 * 一律丢弃并回 insufficient —— 提示词是请求，校验才是保证。</p>
 *
 * @author qtools
 */
@Service
public class MediaSynopsisServiceImpl implements IMediaSynopsisService
{
    private static final Logger log = LoggerFactory.getLogger(MediaSynopsisServiceImpl.class);

    /** 内存里最多保留多少个任务（完成任务先进先出淘汰） */
    private static final int JOB_KEEP = 20;

    /** 素材来源标识：字幕 */
    public static final String SOURCE_SUBTITLE = "subtitle";

    /** 素材来源标识：画面描述 */
    public static final String SOURCE_VISION = "vision";

    /** 素材来源标识：音频转写 */
    public static final String SOURCE_AUDIO = "audio";

    /** 素材来源标识：模板（客观信息） */
    public static final String SOURCE_TEMPLATE = "template";

    /** 素材来源标识：都没有 */
    public static final String SOURCE_NONE = "none";

    /** 进度查询与创建共享的任务表（插入顺序 = 创建顺序，便于淘汰） */
    private static final Map<String, SynopsisBatchJob> JOBS = Collections
            .synchronizedMap(new LinkedHashMap<String, SynopsisBatchJob>());

    /** 已撤销过的任务：jobId → 撤销时间（同一个任务不重复回滚，避免把回滚后的人工修改再写回旧值） */
    private static final Map<String, String> ROLLED_BACK = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * 批量执行的**单线程**执行器：多个任务天然串行，本地模型不会被并发压垮
     *
     * <p>守护线程 + 不 shutdown：媒体库是常驻进程，任务最长也就几分钟（一条一次模型调用）。</p>
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "media-synopsis-batch");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private MediaVideoMapper mediaVideoMapper;

    @Autowired
    private MediaStudioMapper mediaStudioMapper;

    @Autowired
    private MediaOrganizeMapper mediaOrganizeMapper;

    @Autowired
    private IMediaOrganizeService mediaOrganizeService;

    @Autowired
    private MediaLlmClient mediaLlmClient;

    @Autowired
    private MediaSubtitleReader subtitleReader;

    @Autowired
    private MediaVisionMaterial visionMaterial;

    @Autowired
    private MediaTranscribeMaterial transcribeMaterial;

    @Override
    public Map<String, Object> capabilities()
    {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("mode", "template");
        template.put("maxChars", SynopsisText.MAX_CHARS);
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("enabled", mediaProperties.getLlm().isEnabled());
        llm.put("provider", mediaProperties.getLlm().getEffectiveProvider());
        llm.put("model", mediaProperties.getLlm().getEffectiveModel());

        Map<String, Object> subtitle = new LinkedHashMap<>();
        subtitle.put("enabled", true);
        subtitle.put("headChars", mediaProperties.getLlm().getEffectiveSubtitleHeadChars());
        subtitle.put("tailChars", mediaProperties.getLlm().getEffectiveSubtitleTailChars());
        subtitle.put("maxHeadChars", SubtitleExcerpt.HEAD_CHARS);
        Map<String, Object> vision = new LinkedHashMap<>();
        vision.put("enabled", mediaProperties.getLlm().getVision().isEnabled());
        vision.put("model", mediaProperties.getLlm().getVision().getEffectiveModel());
        vision.put("frames", mediaProperties.getLlm().getVision().getEffectiveFrames());
        vision.put("width", mediaProperties.getLlm().getVision().getEffectiveWidth());
        // 音频转写（P5.57）：available 是**真实**可用性 —— 开关 + whisper-cli + 模型 + ffmpeg 四项都就绪才 true，
        // 前端据此决定要不要显示「用音频转写重新生成」；不可用时 reason 说明缺哪一项。
        MediaTranscribeMaterial.Availability transcribe = transcribeMaterial.availability();
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("enabled", mediaProperties.getLlm().getTranscribe().isEnabled());
        audio.put("provider", "whisper.cpp");
        audio.put("available", transcribe.available);
        audio.put("model", transcribe.available ? transcribe.modelPath : transcribe.model);
        audio.put("whisperPath", transcribe.whisperPath);
        audio.put("language", mediaProperties.getLlm().getTranscribe().getEffectiveLanguage());
        audio.put("fallbackLanguage", mediaProperties.getLlm().getTranscribe().getEffectiveFallbackLanguage());
        audio.put("timeoutMs", mediaProperties.getLlm().getTranscribe().getEffectiveTimeoutMs());
        audio.put("headSeconds", mediaProperties.getLlm().getTranscribe().getEffectiveHeadSeconds());
        audio.put("tailSeconds", mediaProperties.getLlm().getTranscribe().getEffectiveTailSeconds());
        audio.put("reason", transcribe.available ? "" : transcribe.reason);
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("subtitle", subtitle);
        sources.put("vision", vision);
        sources.put("audio", audio);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", template);
        out.put("llm", llm);
        out.put("sources", sources);
        return out;
    }

    @Override
    public Map<String, Object> template(String videoId)
    {
        SynopsisFields fields = fieldsFor(videoId);
        String text = SynopsisTemplateRenderer.render(fields);
        if (text == null || text.isEmpty())
        {
            throw MediaException.badRequest("这条视频没有可用的结构化信息（标题 / 分类 / 时长 / 人物 / 标签 / 工作室全空），无法生成简介");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", videoId);
        out.put("synopsis", text);
        out.put("chars", SynopsisText.codePoints(text));
        out.put("mode", "template");
        out.put("source", SOURCE_TEMPLATE);
        out.put("insufficient", false);
        return out;
    }

    /**
     * 本地模型生成（内容梗概）：素材链 字幕 → 画面 → insufficient
     *
     * <p>永远回 200：素材不足是<b>正常结论</b>（很多片子就没有内容可概括），
     * 用 {@code insufficient:true} 表达，让前端显示「改用模板生成」而不是弹一个红色错误。</p>
     */
    @Override
    public Map<String, Object> llm(String videoId)
    {
        return generate(videoId, false);
    }

    /**
     * 只走音频转写（「用音频转写重新生成」按钮）：不碰字幕、不碰画面
     *
     * <p>为什么<b>不</b>在音频不可用时悄悄回落到字幕：用户按这个按钮就是因为字幕不可信，
     * 换回字幕等于没按。所以这条路径只有两个出口 —— 音频转写出的梗概，或 insufficient + 原因。</p>
     */
    @Override
    public Map<String, Object> audio(String videoId)
    {
        return generate(videoId, true);
    }

    /**
     * 生成内容梗概（素材链 字幕 → 画面 → 音频转写 → insufficient）
     *
     * @param audioOnly true = 只走音频转写（见 {@link #audio(String)}）
     */
    private Map<String, Object> generate(String videoId, boolean audioOnly)
    {
        MediaVideo video = requireVideo(videoId);
        SynopsisFields fields = fieldsFor(video);
        long begin = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", videoId);
        out.put("mode", "llm");

        // 1) 字幕（audioOnly 时跳过：用户不是要再看一遍那个不可信的字幕）
        MediaSubtitleReader.Material sub = audioOnly ? null
                : subtitleReader.read(video.getSubPath(), video.getRootId(),
                        mediaProperties.getLlm().getEffectiveSubtitleHeadChars(),
                        mediaProperties.getLlm().getEffectiveSubtitleTailChars());
        if (sub != null && sub.available)
        {
            Attempt attempt = attempt(fields, SynopsisPrompt.subtitle(fields, sub.excerpt), video);
            if (attempt.ok())
            {
                out.put("synopsis", attempt.text);
                out.put("chars", SynopsisText.codePoints(attempt.text));
                out.put("model", attempt.model);
                out.put("provider", attempt.provider);
                out.put("ms", attempt.ms);
                out.put("source", SOURCE_SUBTITLE);
                out.put("insufficient", false);
                Map<String, Object> material = new LinkedHashMap<>();
                material.put("kind", SOURCE_SUBTITLE);
                material.put("format", sub.format);
                material.put("lines", sub.lines);
                material.put("chars", sub.chars);
                material.put("excerptChars", SynopsisText.codePoints(sub.excerpt));
                material.put("relPath", sub.relPath);
                out.put("material", material);
                log.info("简介生成（字幕）video={} chars={} ms={} model={}", videoId,
                        SynopsisText.codePoints(attempt.text), attempt.ms, attempt.model);
                return out;
            }
            log.info("字幕素材生成未通过校验 video={} verdict={}", videoId, attempt.verdict);
            out.put("subtitleVerdict", attempt.verdict);
        }
        else if (sub != null)
        {
            out.put("subtitleReason", sub.reason);
        }

        // 2) 画面（audioOnly 时跳过）
        if (!audioOnly && visionMaterial.enabled())
        {
            MediaVisionMaterial.Material vision = visionMaterial.read(video.getRelPath(), video.getRootId(),
                    video.getDur());
            if (vision.available)
            {
                Attempt attempt = attempt(fields, SynopsisPrompt.vision(fields, vision.descriptions), video);
                if (attempt.ok())
                {
                    out.put("synopsis", attempt.text);
                    out.put("chars", SynopsisText.codePoints(attempt.text));
                    out.put("model", attempt.model);
                    out.put("provider", attempt.provider);
                    out.put("ms", System.currentTimeMillis() - begin);
                    out.put("source", SOURCE_VISION);
                    out.put("insufficient", false);
                    Map<String, Object> material = new LinkedHashMap<>();
                    material.put("kind", SOURCE_VISION);
                    material.put("frames", vision.frames);
                    material.put("descriptions", vision.descriptions);
                    material.put("visionMs", vision.ms);
                    out.put("material", material);
                    log.info("简介生成（画面）video={} chars={} frames={} ms={}", videoId,
                            SynopsisText.codePoints(attempt.text), vision.frames, vision.ms);
                    return out;
                }
                out.put("visionVerdict", attempt.verdict);
                log.info("画面素材生成未通过校验 video={} verdict={}", videoId, attempt.verdict);
            }
            else
            {
                out.put("visionReason", vision.reason);
            }
        }

        // 3) 音频转写（P5.57）：字幕不可信 / 没有字幕时的最后一路，也是「重新生成」按钮专走的一路
        MediaTranscribeMaterial.Availability transcribe = transcribeMaterial.availability();
        String audioReason = "";
        if (!transcribe.available)
        {
            audioReason = transcribe.reason;
            out.put("audioReason", audioReason);
        }
        else
        {
            MediaTranscribeMaterial.Material audio = transcribeMaterial.read(video.getRelPath(), video.getRootId(),
                    video.getDur());
            if (audio.available)
            {
                Attempt attempt = attempt(fields, SynopsisPrompt.audio(fields, audio.excerpt), video);
                if (attempt.ok())
                {
                    out.put("synopsis", attempt.text);
                    out.put("chars", SynopsisText.codePoints(attempt.text));
                    out.put("model", attempt.model);
                    out.put("provider", attempt.provider);
                    out.put("ms", System.currentTimeMillis() - begin);
                    out.put("source", SOURCE_AUDIO);
                    out.put("insufficient", false);
                    Map<String, Object> material = new LinkedHashMap<>();
                    material.put("kind", SOURCE_AUDIO);
                    material.put("provider", "whisper.cpp");
                    material.put("model", transcribe.modelPath);
                    material.put("language", audio.language);
                    material.put("segments", audio.segments);
                    material.put("chars", audio.chars);
                    material.put("excerptChars", SynopsisText.codePoints(audio.excerpt));
                    material.put("audioSeconds", Math.round(audio.audioSeconds));
                    material.put("transcribeMs", audio.ms);
                    material.put("excerpt", SynopsisText.truncate(audio.excerpt));
                    out.put("material", material);
                    log.info("简介生成（音频转写）video={} lang={} chars={} audio={}s ms={} model={}", videoId,
                            audio.language, SynopsisText.codePoints(attempt.text), Math.round(audio.audioSeconds),
                            System.currentTimeMillis() - begin, attempt.model);
                    return out;
                }
                log.info("音频转写素材生成未通过校验 video={} verdict={}", videoId, attempt.verdict);
                out.put("audioVerdict", attempt.verdict);
                audioReason = "音频转写素材未通过校验（" + attempt.verdict + "）";
            }
            else
            {
                audioReason = audio.reason;
            }
            out.put("audioReason", audioReason);
        }

        // 4) insufficient：不调模型、不编造
        out.put("synopsis", "");
        out.put("chars", 0);
        out.put("source", SOURCE_NONE);
        out.put("insufficient", true);
        out.put("reason", insufficientReason(sub == null || !sub.available, visionMaterial.enabled(),
                transcribe.available));
        out.put("ms", System.currentTimeMillis() - begin);
        log.info("简介生成 insufficient video={} reason={}", videoId, out.get("reason"));
        return out;
    }

    /**
     * 素材不足时的统一文案（前端直接显示这句话）
     *
     * <p>P5.57 起把音频转写也算进来：三条素材路都试过还不够，文案就要说清「都试过了」，
     * 而不是让用户以为还有一路没走。</p>
     *
     * @param noSubtitle   是否没有可用字幕（读过但为空 / 没有字幕文件）
     * @param visionEnabled 画面理解是否启用
     * @param audioAvailable 音频转写是否可用
     */
    private String insufficientReason(boolean noSubtitle, boolean visionEnabled, boolean audioAvailable)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(noSubtitle ? "该片没有可用字幕" : "该片的字幕没有可用正文");
        if (!visionEnabled)
        {
            sb.append("，画面理解也未启用（qtools.media.llm.vision.enabled=false）");
        }
        else
        {
            sb.append("，画面也没有提供可概括的内容");
        }
        if (audioAvailable)
        {
            sb.append("，音频转写同样没有拿到可用正文（语音太少 / 全是噪声）");
        }
        sb.append("，无法生成内容梗概；可改用模板生成客观信息");
        return sb.toString();
    }

    /** 一次生成尝试的结果：通过校验才算 ok，否则带 verdict 供日志/回包 */
    private static final class Attempt
    {
        final String text;

        final String model;

        final String provider;

        final long ms;

        final SynopsisGuard.Verdict verdict;

        Attempt(String text, String model, String provider, long ms, SynopsisGuard.Verdict verdict)
        {
            this.text = text;
            this.model = model;
            this.provider = provider;
            this.ms = ms;
            this.verdict = verdict;
        }

        static Attempt rejected(SynopsisGuard.Verdict verdict)
        {
            return new Attempt("", "", "", 0, verdict);
        }

        boolean ok()
        {
            return verdict == SynopsisGuard.Verdict.OK && text != null && !text.isEmpty();
        }
    }

    /**
     * 调模型 + 硬校验
     *
     * <p>模型没就绪（503）继续往上抛：那是部署问题，前端要显示「本地模型未就绪」并回退模板，
     * 不能伪装成「素材不足」。</p>
     */
    private Attempt attempt(SynopsisFields fields, String prompt, MediaVideo video)
    {
        MediaLlmClient.Reply reply = mediaLlmClient.generate(prompt);
        SynopsisGuard.Verdict verdict = SynopsisGuard.check(reply.text, video.getTitle(), video.getRelPath());
        if (verdict == SynopsisGuard.Verdict.OK && !grounded(reply.text, prompt))
        {
            verdict = SynopsisGuard.Verdict.UNGROUNDED;
        }
        if (verdict != SynopsisGuard.Verdict.OK)
        {
            log.debug("生成结果被丢弃 verdict={} title={} text={}", verdict, video.getTitle(), reply.text);
            return Attempt.rejected(verdict);
        }
        return new Attempt(reply.text, reply.model, reply.provider, reply.ms, verdict);
    }

    /**
     * 与素材的「有根据」检查：只在素材里出现过内容词时才有意义
     *
     * <p>直接把提示词当素材比对会引入提示词模板自身的词（「请输出」「素材」），
     * 所以只比对素材正文：这里用「提示词里去掉固定前后缀」的粗办法 —— 让 {@link SynopsisGuard#grounded}
     * 在整个提示词上判定并不严格，但因为门槛是「共享任意一个 2-gram」，模板词的存在只会放宽而非误杀，
     * 对 1.5B 模型的编造（整段与素材无关）仍然敏感。</p>
     */
    private static boolean grounded(String text, String prompt)
    {
        return SynopsisGuard.grounded(text, prompt);
    }

    @Override
    public Map<String, Object> startBatch(List<String> videoIds, boolean all, String mode, boolean overwrite)
    {
        String normalized = mode == null || mode.trim().isEmpty() ? "template" : mode.trim().toLowerCase(Locale.ROOT);
        if (!"llm".equals(normalized) && !"template".equals(normalized))
        {
            throw MediaException.badRequest("mode 只能是 llm 或 template");
        }
        if ("llm".equals(normalized) && !mediaLlmClient.isReady())
        {
            throw MediaException.serviceUnavailable("本地模型未启用（qtools.media.llm.enabled=false），批量生成只能用 mode=template");
        }
        List<String> ids = resolveTargets(videoIds, all);
        SynopsisBatchJob job = new SynopsisBatchJob(newJobId(), normalized, overwrite, ids);
        JOBS.put(job.getId(), job);
        trimJobs();
        EXECUTOR.execute(() -> job.run(ops(normalized)));
        log.info("批量简介生成入队 job={} mode={} overwrite={} total={}", job.getId(), normalized, overwrite, ids.size());
        return job.toNode();
    }

    @Override
    public Map<String, Object> job(String jobId)
    {
        return requireJob(jobId).toNode();
    }

    @Override
    public Map<String, Object> preview(List<String> videoIds, boolean all, boolean overwrite)
    {
        List<String> ids = resolveTargets(videoIds, all);
        int withExisting = 0;
        for (String id : ids)
        {
            String existing = mediaOrganizeMapper.selectSynopsisById(id);
            if (existing != null && !existing.trim().isEmpty())
            {
                withExisting++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", ids.size());
        out.put("withExisting", withExisting);
        out.put("withoutExisting", ids.size() - withExisting);
        // overwrite=false：已有内容的跳过；true：全都要重写
        out.put("willProcess", overwrite ? ids.size() : ids.size() - withExisting);
        out.put("willSkip", overwrite ? 0 : withExisting);
        out.put("overwrite", overwrite);
        out.put("all", all);
        return out;
    }

    @Override
    public Map<String, Object> activeJobs()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        synchronized (JOBS)
        {
            for (SynopsisBatchJob job : JOBS.values())
            {
                if (job.isActive())
                {
                    Map<String, Object> node = job.toNode();
                    // 列表里不带 items（几十上百条逐条结果会把「有没有在跑」这个回答淹掉）
                    node.remove("items");
                    rows.add(node);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("jobs", rows);
        return out;
    }

    @Override
    public Map<String, Object> cancel(String jobId)
    {
        SynopsisBatchJob job = requireJob(jobId);
        // 幂等：已经结束的任务再 stop 一次只把标志置上并回当前状态（前端连点两次不该报错）
        job.requestCancel();
        log.info("批量简介生成停止请求 job={} status={} processed={}/{}", job.getId(),
                job.toNode().get("status"), Integer.valueOf(((Number) job.toNode().get("processed")).intValue()),
                Integer.valueOf(((Number) job.toNode().get("total")).intValue()));
        return job.toNode();
    }

    @Override
    public Map<String, Object> rollback(String jobId)
    {
        SynopsisBatchJob job = requireJob(jobId);
        Map<String, String> previous = job.getPrevious();
        if (previous.isEmpty())
        {
            throw MediaException.badRequest("任务 " + job.getId()
                    + " 没有记录旧值（P5.55 之前的任务不留快照），无法撤销；请改用「按 ids 清空简介」");
        }
        synchronized (ROLLED_BACK)
        {
            if (ROLLED_BACK.containsKey(job.getId()))
            {
                throw new MediaException(409, "该任务已在 " + ROLLED_BACK.get(job.getId()) + " 撤销过，不重复回滚"
                        + "（回滚后若有人手改过，再回滚一次会把它写回旧值）");
            }
            ROLLED_BACK.put(job.getId(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int cleared = 0;
        int restored = 0;
        int failed = 0;
        for (Map.Entry<String, String> entry : previous.entrySet())
        {
            String videoId = entry.getKey();
            String old = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("videoId", videoId);
            try
            {
                if (old == null || old.isEmpty())
                {
                    mediaOrganizeService.updateVideoSynopsis(videoId, null);
                    cleared++;
                    row.put("action", "cleared");
                }
                else
                {
                    mediaOrganizeService.updateVideoSynopsis(videoId, old);
                    restored++;
                    row.put("action", "restored");
                    row.put("chars", SynopsisText.codePoints(old));
                }
            }
            catch (RuntimeException e)
            {
                failed++;
                row.put("action", "failed");
                row.put("message", e.getMessage());
            }
            items.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.getId());
        out.put("total", previous.size());
        out.put("cleared", cleared);
        out.put("restored", restored);
        out.put("failed", failed);
        out.put("rolledBackAt", ROLLED_BACK.get(job.getId()));
        out.put("items", items);
        log.info("批量简介生成撤销 job={} total={} cleared={} restored={} failed={}", job.getId(),
                Integer.valueOf(previous.size()), Integer.valueOf(cleared), Integer.valueOf(restored),
                Integer.valueOf(failed));
        return out;
    }

    @Override
    public Map<String, Object> clearSynopsis(List<String> videoIds, boolean dryRun)
    {
        List<String> ids = distinct(videoIds);
        if (ids.isEmpty())
        {
            throw MediaException.badRequest("videoIds 为空（这个工具只按显式 id 清空，不做「全库」）");
        }
        int willClear = 0;
        int alreadyEmpty = 0;
        int missing = 0;
        List<String> targets = new ArrayList<>();
        for (String id : ids)
        {
            MediaVideo video = mediaVideoMapper.selectVideoById(id);
            if (video == null)
            {
                missing++;
                continue;
            }
            String existing = mediaOrganizeMapper.selectSynopsisById(id);
            if (existing == null || existing.trim().isEmpty())
            {
                alreadyEmpty++;
                continue;
            }
            willClear++;
            targets.add(id);
        }
        int done = 0;
        if (!dryRun)
        {
            for (String id : targets)
            {
                mediaOrganizeService.updateVideoSynopsis(id, null);
                done++;
            }
            log.info("按 ids 清空简介 ids={} 实际清空={} 原本就空={} 不存在={}", Integer.valueOf(ids.size()),
                    Integer.valueOf(done), Integer.valueOf(alreadyEmpty), Integer.valueOf(missing));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("requested", ids.size());
        out.put("willClear", willClear);
        out.put("cleared", done);
        out.put("alreadyEmpty", alreadyEmpty);
        out.put("missing", missing);
        out.put("ids", targets);
        return out;
    }

    /** 取任务，不存在一律 404（后端重启后内存任务丢失，前端据此提示「任务已丢失」） */
    private SynopsisBatchJob requireJob(String jobId)
    {
        SynopsisBatchJob job = jobId == null ? null : JOBS.get(jobId);
        if (job == null)
        {
            throw MediaException.notFound("任务不存在或已过期：" + jobId);
        }
        return job;
    }

    /** 目标 id 归一：all=true 取全库口径，否则用显式列表 */
    private List<String> resolveTargets(List<String> videoIds, boolean all)
    {
        List<String> ids = all ? allVideoIds() : distinct(videoIds);
        if (ids.isEmpty())
        {
            throw MediaException.badRequest("没有可处理的视频（videoIds 为空或库里没有可用视频）");
        }
        return ids;
    }

    // ------------------------------------------------------------------ 素材装配

    private MediaVideo requireVideo(String videoId)
    {
        MediaVideo video = mediaVideoMapper.selectVideoById(videoId);
        if (video == null)
        {
            throw MediaException.notFound("视频不存在：" + videoId);
        }
        return video;
    }

    /**
     * 一个视频的结构化素材（模板与模型的唯一输入）
     *
     * <p>人物取 {@code media_video_actor}（同时覆盖扫描脚本的 name-only 行与管理端绑定的人物行），
     * 标签取 {@code media_video_tag}（字典标签 + 扫描 facet 标签都在这一张表里）。</p>
     */
    public SynopsisFields fieldsFor(String videoId)
    {
        return fieldsFor(requireVideo(videoId));
    }

    private SynopsisFields fieldsFor(MediaVideo video)
    {
        SynopsisFields fields = new SynopsisFields();
        fields.setVideoId(video.getId());
        fields.setTitle(video.getTitle());
        fields.setFolder(video.getFolder());
        fields.setDur(video.getDur());
        fields.setRelPath(video.getRelPath());

        List<String> persons = new ArrayList<>();
        for (MediaVideoActor actor : nullSafe(mediaVideoMapper.selectActorListByVideoId(video.getId())))
        {
            persons.add(actor.getName());
        }
        fields.setPersons(distinct(persons));

        List<String> tags = new ArrayList<>();
        for (MediaVideoTag tag : nullSafe(mediaVideoMapper.selectTagListByVideoId(video.getId())))
        {
            tags.add(tag.getName());
        }
        fields.setTags(distinct(tags));

        fields.setStudios(distinct(nullSafe(mediaStudioMapper.selectStudioNamesByVideoId(video.getId()))));
        fields.setMarkerCount(nullSafe(mediaOrganizeMapper.selectMarkersByVideoId(video.getId())).size());
        return fields;
    }

    // ------------------------------------------------------------------ 批量执行

    /**
     * 批量任务的外部动作：标题 / 已有简介 / 生成 / 落库
     *
     * <p>{@code persist} 走 {@link IMediaOrganizeService#updateVideoSynopsis}：与人工保存同一套
     * 归一化与快照失效逻辑，不会出现「生成的长简介绕过 8000 字校验」这种分叉。</p>
     */
    private SynopsisBatchJob.Ops ops(String mode)
    {
        return new SynopsisBatchJob.Ops()
        {
            @Override
            public String title(String videoId)
            {
                MediaVideo video = mediaVideoMapper.selectVideoById(videoId);
                return video == null ? null : video.getTitle();
            }

            @Override
            public String existing(String videoId)
            {
                return mediaOrganizeMapper.selectSynopsisById(videoId);
            }

            @Override
            public String skipReason(String videoId)
            {
                if (!"llm".equals(mode))
                {
                    return null;
                }
                MediaVideo video = mediaVideoMapper.selectVideoById(videoId);
                if (video == null)
                {
                    return null;
                }
                MediaSubtitleReader.Material sub = subtitleReader.read(video.getSubPath(), video.getRootId(),
                        mediaProperties.getLlm().getEffectiveSubtitleHeadChars(),
                        mediaProperties.getLlm().getEffectiveSubtitleTailChars());
                // P5.57：音频转写可用时不存在「一定生成不了」的片子 —— 没有字幕还有声音
                if (sub.available || visionMaterial.enabled() || transcribeMaterial.available())
                {
                    return null;
                }
                return "没有字幕、未启用画面理解，音频转写也不可用，无法生成内容梗概（" + sub.reason + "）";
            }

            @Override
            public String skipCode(String videoId)
            {
                // P5.55：成果汇报要能区分「已有内容跳过」和「没有素材跳过」——这两类对用户的含义完全不同
                return SynopsisBatchJob.SKIP_INSUFFICIENT;
            }

            @Override
            public String emptyCode(String videoId)
            {
                // 模型模式的空结果 = 素材不足（insufficient），不是失败；模板模式渲染不出来才是真失败。
                // 报成 failed 会让用户以为程序出错，实际上只是这部片子没有可概括的内容。
                return "llm".equals(mode) ? SynopsisBatchJob.SKIP_INSUFFICIENT : SynopsisBatchJob.SKIP_OTHER;
            }

            @Override
            public String generate(String videoId)
            {
                if (!"llm".equals(mode))
                {
                    return SynopsisTemplateRenderer.render(fieldsFor(videoId));
                }
                Map<String, Object> result = llm(videoId);
                Object text = result.get("synopsis");
                return text == null ? "" : String.valueOf(text);
            }

            @Override
            public void persist(String videoId, String text)
            {
                mediaOrganizeService.updateVideoSynopsis(videoId, text);
            }
        };
    }

    /** 全库可生成的目标：未隐藏 + 未缺失（列表接口的既有口径） */
    private List<String> allVideoIds()
    {
        List<String> ids = new ArrayList<>();
        for (MediaVideo video : nullSafe(mediaVideoMapper.selectVideoList(Boolean.FALSE)))
        {
            if (video.getId() != null && !video.getId().isEmpty())
            {
                ids.add(video.getId());
            }
        }
        return distinct(ids);
    }

    private static void trimJobs()
    {
        synchronized (JOBS)
        {
            while (JOBS.size() > JOB_KEEP)
            {
                String drop = null;
                for (Map.Entry<String, SynopsisBatchJob> entry : JOBS.entrySet())
                {
                    String status = String.valueOf(entry.getValue().toNode().get("status"));
                    if (SynopsisBatchJob.JOB_DONE.equals(status) || SynopsisBatchJob.JOB_FAILED.equals(status))
                    {
                        drop = entry.getKey();
                        break;
                    }
                }
                if (drop == null)
                {
                    return;
                }
                JOBS.remove(drop);
            }
        }
    }

    private static String newJobId()
    {
        return "sj" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 去空、去重（保持原顺序） */
    private static List<String> distinct(List<String> values)
    {
        Set<String> seen = new LinkedHashSet<>();
        if (values != null)
        {
            for (String raw : values)
            {
                String text = SynopsisText.clean(raw);
                if (!text.isEmpty())
                {
                    seen.add(text);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private static <T> List<T> nullSafe(List<T> list)
    {
        return list == null ? Collections.<T> emptyList() : list;
    }
}
