package com.qtools.media.support;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaFileService;

/**
 * 画面素材（P5.53 交付 2「视觉摘要」）：抽帧 → 视觉模型逐帧描述 → 供文本模型合成。
 *
 * <h2>为什么这是「无字幕片子」的主路径</h2>
 *
 * <p>库里大量片子没有对白也没有字幕（ASMR / 桌面壁纸 / 写真 / 舞蹈），字幕路线必然覆盖不到。
 * 画面是这类片子唯一的内容载体，所以视觉描述就是它们的「字幕」。</p>
 *
 * <h2>显存现实（8GB 卡）</h2>
 *
 * <p>3B 视觉模型（Q4，约 2.5GB）+ 1.5B 文本模型（约 1GB）+ KV cache 与上下文，同时常驻会逼近
 * 8GB 上限并触发 ollama 反复换页（每帧十几秒）。因此这里<b>串行</b>：先逐帧描述，
 * 结束后立刻 {@link MediaLlmClient#unloadVisionModel()} 把视觉模型赶下显存，再由文本模型合成。
 * 代价是每帧首推理要重新加载（约 5-20s），换来的是稳定不抖动。</p>
 *
 * <p>单帧失败不影响整体：5 帧里拿到 1-2 句描述就足以写梗概，全失败才判定不可用。</p>
 *
 * @author qtools
 */
@Component
public class MediaVisionMaterial
{
    private static final Logger log = LoggerFactory.getLogger(MediaVisionMaterial.class);

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private MediaLlmClient mediaLlmClient;

    /** 一次画面素材的结果 */
    public static final class Material
    {
        public final boolean available;

        /** 每帧一句描述（已过滤「无有效画面」与空串） */
        public final List<String> descriptions;

        /** 实际抽到几帧 */
        public final int frames;

        /** 一共跑了多久（毫秒） */
        public final long ms;

        public final String reason;

        private Material(boolean available, List<String> descriptions, int frames, long ms, String reason)
        {
            this.available = available;
            this.descriptions = descriptions;
            this.frames = frames;
            this.ms = ms;
            this.reason = reason;
        }

        static Material missing(String reason, int frames, long ms)
        {
            return new Material(false, new ArrayList<String>(), frames, ms, reason);
        }
    }

    /** 配置类错误（开关 / 模型名）——这类失败重试无意义，直接放弃整条 */
    private static boolean isConfigError(MediaException e)
    {
        String message = String.valueOf(e.getMessage());
        return message.contains("未启用") || message.contains("HTTP 404") || message.contains("是否存在");
    }

    /** 配置是否打开 */
    public boolean enabled()
    {
        return mediaProperties != null && mediaProperties.getLlm().getVision().isEnabled();
    }

    /**
     * 取画面素材
     *
     * @param relPath 视频相对媒体根的路径（{@code media_video.rel_path}）
     * @param rootId  库根 id（多库根）；null = 默认根 + 多根兜底
     * @param dur     片长（秒）；null / 0 = 未知（退化成只取第 1 秒）
     */
    public Material read(String relPath, Integer rootId, BigDecimal dur)
    {
        long begin = System.currentTimeMillis();
        MediaProperties.Vision cfg = mediaProperties.getLlm().getVision();
        if (!cfg.isEnabled())
        {
            return Material.missing("画面理解未启用（qtools.media.llm.vision.enabled=false）", 0, 0);
        }
        String ffmpeg = mediaProperties.getFfmpeg();
        if (ffmpeg == null || ffmpeg.trim().isEmpty())
        {
            return Material.missing("未配置 ffmpeg（qtools.media.ffmpeg），无法抽帧", 0, 0);
        }
        if (relPath == null || relPath.trim().isEmpty())
        {
            return Material.missing("该片没有可定位的媒体文件（rel_path 为空）", 0, 0);
        }
        Path video;
        try
        {
            video = mediaFileService.resolveMedia(relPath, rootId);
        }
        catch (MediaException e)
        {
            return Material.missing("视频文件定位失败：" + e.getMessage(), 0, System.currentTimeMillis() - begin);
        }
        double seconds = MediaFrameGrab.seconds(dur);
        List<Integer> points = MediaFrameGrab.sampleSeconds(seconds, cfg.getEffectiveFrames());
        int width = cfg.getEffectiveWidth();
        List<String> descriptions = new ArrayList<>();
        int grabbed = 0;
        try
        {
            for (Integer sec : points)
            {
                byte[] jpeg = MediaFrameGrab.grab(ffmpeg, video, sec, width);
                if (jpeg.length == 0)
                {
                    continue;
                }
                grabbed++;
                String text;
                try
                {
                    text = mediaLlmClient.describeImage(jpeg, VisionPrompt.FRAME);
                }
                catch (MediaException e)
                {
                    log.debug("单帧描述失败 t={}s err={}", sec, e.getMessage());
                    if (descriptions.isEmpty() && isConfigError(e))
                    {
                        // 开关没开 / 模型不存在这类错误不必把剩下 4 帧再跑一遍
                        return Material.missing(e.getMessage(), grabbed, System.currentTimeMillis() - begin);
                    }
                    continue;
                }
                String clean = SynopsisText.clean(text);
                if (clean.isEmpty() || clean.contains(VisionPrompt.NO_CONTENT))
                {
                    continue;
                }
                descriptions.add(clean);
            }
        }
        finally
        {
            mediaLlmClient.unloadVisionModel();
        }
        long ms = System.currentTimeMillis() - begin;
        if (descriptions.isEmpty())
        {
            return Material.missing("抽到 " + grabbed + " 帧，但视觉模型没有给出可用的画面描述", grabbed, ms);
        }
        log.debug("画面素材就绪 frames={}/{} descriptions={} ms={}", grabbed, points.size(), descriptions.size(), ms);
        return new Material(true, descriptions, grabbed, ms, "");
    }
}
