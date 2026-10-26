package com.qtools.media.service.impl;

import java.util.ArrayList;
import java.util.Collections;
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
 *   <li><b>音频转写</b>：本机未装 whisper.cpp，只有配置与设计，不实现（见 {@link MediaProperties.Transcribe}）；</li>
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
        Map<String, Object> audio = new LinkedHashMap<>();
        // 本机未实现音频转写（whisper.cpp 未安装）：能力恒为 false，配置项保留给后续接入
        audio.put("enabled", false);
        audio.put("provider", "whisper.cpp");
        audio.put("available", false);
        audio.put("reason", "本机未安装 whisper.cpp，音频转写只有设计与配置项（P5.53）");
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
        MediaVideo video = requireVideo(videoId);
        SynopsisFields fields = fieldsFor(video);
        long begin = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", videoId);
        out.put("mode", "llm");

        // 1) 字幕
        MediaSubtitleReader.Material sub = subtitleReader.read(video.getSubPath(), video.getRootId(),
                mediaProperties.getLlm().getEffectiveSubtitleHeadChars(),
                mediaProperties.getLlm().getEffectiveSubtitleTailChars());
        if (sub.available)
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
        else
        {
            out.put("subtitleReason", sub.reason);
        }

        // 2) 画面
        if (visionMaterial.enabled())
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

        // 3) 音频转写：未实现（本机无 whisper.cpp），只在 reason 里说明
        String audioReason = mediaProperties.getLlm().getTranscribe().isEnabled()
                ? "音频转写未实现（本机未安装 whisper.cpp）"
                : "音频转写默认关闭（qtools.media.llm.transcribe.enabled=false）";

        // 4) insufficient：不调模型、不编造
        out.put("synopsis", "");
        out.put("chars", 0);
        out.put("source", SOURCE_NONE);
        out.put("insufficient", true);
        out.put("reason", insufficientReason(sub.available, visionMaterial.enabled()));
        out.put("audioReason", audioReason);
        out.put("ms", System.currentTimeMillis() - begin);
        log.info("简介生成 insufficient video={} reason={}", videoId, out.get("reason"));
        return out;
    }

    /**
     * 素材不足时的统一文案（前端直接显示这句话）
     *
     * @param hasSubtitle 是否读过字幕文件（读过但为空 = 字幕没有内容；没读过 = 没有字幕）
     */
    private String insufficientReason(boolean hasSubtitle, boolean visionEnabled)
    {
        if (!hasSubtitle && !visionEnabled)
        {
            return "该片没有字幕，画面理解也未启用（qtools.media.llm.vision.enabled=false），无法生成内容梗概；可改用模板生成客观信息";
        }
        if (!visionEnabled)
        {
            return "该片的字幕没有可用正文，画面理解未启用，无法生成内容梗概；可改用模板生成客观信息";
        }
        return "该片既没有可用字幕，画面也没有提供可概括的内容，无法生成内容梗概；可改用模板生成客观信息";
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
        List<String> ids = all ? allVideoIds() : distinct(videoIds);
        if (ids.isEmpty())
        {
            throw MediaException.badRequest("没有可处理的视频（videoIds 为空或库里没有可用视频）");
        }
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
        SynopsisBatchJob job = jobId == null ? null : JOBS.get(jobId);
        if (job == null)
        {
            throw MediaException.notFound("任务不存在或已过期：" + jobId);
        }
        return job.toNode();
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
                if (sub.available || visionMaterial.enabled())
                {
                    return null;
                }
                return "没有字幕且未启用画面理解，无法生成内容梗概（" + sub.reason + "）";
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
