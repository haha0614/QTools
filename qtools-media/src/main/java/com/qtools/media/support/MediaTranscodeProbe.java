package com.qtools.media.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.qtools.common.utils.StringUtils;
import com.qtools.media.config.MediaProperties;

/**
 * 硬件编码器探测服务：把「配置想要什么」翻译成「这台机器实际能用什么」
 *
 * <p><b>懒加载</b>：第一次真正要转码（或管理端读 {@code /config}）时才执行
 * {@code ffmpeg -hide_banner -encoders}，结果缓存在内存（本进程生命周期内不再重探），
 * 只记一条 INFO 日志。启动阶段不碰磁盘/GPU：媒体库部署在没有显卡的机器上也很常见，
 * 那时探测只是多花 200ms 且必然回退软件。</p>
 *
 * <p><b>解析</b>：只认 {@code ffmpeg -encoders} 的清单，判断标准是「列表里有没有这个编码器名」。
 * 注意这只证明<b>二进制带了这个编码器</b>，不证明<b>硬件可用</b>（NVENC 在没显卡 / 驱动版本不匹配时
 * 照样会出现在清单里，真跑起来才返回非 0）。后者由 {@code MediaStreamServiceImpl} 的
 * 「失败回退软件重试一次」兜住：真失败时调 {@link #markUnavailable(String)}，本进程后续请求直接走软件。</p>
 *
 * <p><b>auto 链</b>：{@code h264_nvenc → h264_qsv → libx264}。</p>
 *
 * <p><b>配置校验</b>：{@code qtools.media.transcode.encoder} 只接受
 * auto / software / libx264 / h264_nvenc / hevc_nvenc / av1_nvenc / h264_qsv / hevc_qsv；
 * 其它值记 WARN 并按 auto 处理（站端接口本身不接受编码器参数，所以「非法配置」只会来自 yml，
 * 用「启动 warn + 回退 auto」而不是 400——一个拼错的 yml 不该让整个媒体库起不来）。</p>
 *
 * @author qtools
 */
@Component
public class MediaTranscodeProbe
{
    private static final Logger log = LoggerFactory.getLogger(MediaTranscodeProbe.class);

    /** 配置值：自动 */
    public static final String AUTO = "auto";

    /** 配置值：强制软件 */
    public static final String SOFTWARE = "software";

    /** 探测命令的超时：ffmpeg 打印编码器清单是毫秒级，5s 已是极端宽容 */
    private static final long PROBE_TIMEOUT_MS = 5000L;

    /** {@code ffmpeg -encoders} 的一行：{@code " V....D h264_nvenc  NVIDIA NVENC ..."} */
    private static final Pattern ENCODER_LINE = Pattern.compile("^\\s*[VAS][A-Z.]{5}\\s+([A-Za-z0-9_]+)\\b.*$");

    /** auto 的候选顺序（硬件优先，软件兜底） */
    private static final List<String> AUTO_CHAIN = List.of("h264_nvenc", "h264_qsv",
            MediaTranscodeCommand.SOFTWARE_ENCODER);

    private final MediaProperties mediaProperties;

    /** 实际生效的编码器；null = 还没探测 */
    private volatile String effective;

    /** 探测到的目标编码器（只在 describe 出参里用） */
    private volatile List<String> available = List.of();

    /** 是否发生过「硬件运行失败 → 进程内降级软件」 */
    private volatile boolean degraded;

    /** 探测失败的原因（ffmpeg 缺失 / 超时），非空时 effective 恒为软件 */
    private volatile String probeError;

    public MediaTranscodeProbe(MediaProperties mediaProperties)
    {
        this.mediaProperties = mediaProperties;
    }

    // ------------------------------------------------------------------ 对外

    /** 实际生效的编码器（首次调用触发探测并缓存） */
    public String effective()
    {
        String cached = effective;
        if (cached != null)
        {
            return cached;
        }
        return resolve();
    }

    /** 配置里写的值（规范化后：auto / software / libx264 / 硬件名），不触发探测 */
    public String requested()
    {
        return normalizeRequested(mediaProperties.getTranscode().getEncoder());
    }

    /** 是否硬件编码器（回退判定用） */
    public boolean isHardware(String encoder)
    {
        return MediaTranscodeCommand.isHardware(encoder);
    }

    /**
     * 标记某个硬件编码器在本机<b>跑不动</b>：当前生效时降级为软件
     *
     * <p>由转码路径在「硬件进程非 0 / 首字节拿不到」时调用。只降不升：一次失败说明这台机器的
     * 驱动/显卡对这条命令不成立，没必要让每个请求都先失败一次。</p>
     */
    public void markUnavailable(String encoder)
    {
        if (!MediaTranscodeCommand.isHardware(encoder))
        {
            return;
        }
        synchronized (this)
        {
            if (encoder.equals(effective))
            {
                degraded = true;
                effective = MediaTranscodeCommand.SOFTWARE_ENCODER;
                log.warn("硬件编码器 {} 实际转码失败，本进程内降级为 {}（后续请求不再重试硬件）", encoder,
                        MediaTranscodeCommand.SOFTWARE_ENCODER);
            }
        }
    }

    /**
     * 管理端自检用：{@code {requested, effective, hardware, degraded, probeError, available, preset, cq}}
     *
     * <p>这是「配置说 nvenc，但机器实际走软件」的唯一可视出口——{@code GET /api/media/admin/config}
     * 会把它原样挂在 {@code media.transcode} 下。</p>
     */
    public Map<String, Object> describe()
    {
        String enc = effective();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", requested());
        out.put("effective", enc);
        out.put("hardware", MediaTranscodeCommand.isHardware(enc));
        out.put("degraded", degraded);
        out.put("probeError", probeError);
        out.put("available", available);
        out.put("preset", mediaProperties.getTranscode().getPreset());
        out.put("cq", mediaProperties.getTranscode().getCq());
        return out;
    }

    // ------------------------------------------------------------------ 探测

    private synchronized String resolve()
    {
        if (effective != null)
        {
            return effective;
        }
        String requested = requested();
        List<String> found = probeEncoders();
        available = found;
        String chosen;
        if (MediaTranscodeCommand.SOFTWARE_ENCODER.equals(requested))
        {
            chosen = MediaTranscodeCommand.SOFTWARE_ENCODER;
        }
        else if (AUTO.equals(requested))
        {
            chosen = firstUsable(found);
        }
        else if (found.contains(requested))
        {
            chosen = requested;
        }
        else
        {
            chosen = firstUsable(found);
            log.warn("配置的编码器 {} 不在本机 ffmpeg 的 -encoders 清单里，回退 {}", requested, chosen);
        }
        effective = chosen;
        log.info("媒体转码编码器 配置={} 实际={} 硬件={} 可用={}", requested, chosen,
                MediaTranscodeCommand.isHardware(chosen), found);
        return chosen;
    }

    /** auto 链取第一个可用的；一个硬件都没有就是软件 */
    private static String firstUsable(List<String> found)
    {
        for (String candidate : AUTO_CHAIN)
        {
            if (MediaTranscodeCommand.SOFTWARE_ENCODER.equals(candidate) || found.contains(candidate))
            {
                return candidate;
            }
        }
        return MediaTranscodeCommand.SOFTWARE_ENCODER;
    }

    /** 执行 {@code ffmpeg -hide_banner -encoders} 并只留下我们关心的编码器名 */
    private List<String> probeEncoders()
    {
        List<String> wanted = new ArrayList<>();
        wanted.add(MediaTranscodeCommand.SOFTWARE_ENCODER);
        wanted.addAll(MediaTranscodeCommand.HARDWARE_ENCODERS);
        String ffmpeg = mediaProperties.getFfmpeg();
        if (StringUtils.isEmpty(ffmpeg))
        {
            probeError = "qtools.media.ffmpeg 未配置，无法探测（按软件转码处理）";
            log.warn(probeError);
            return List.of();
        }
        Path temp = null;
        Process process = null;
        try
        {
            temp = Files.createTempFile("qtools-encoders-", ".txt");
            ProcessBuilder builder = new ProcessBuilder(ffmpeg, "-hide_banner", "-encoders");
            builder.redirectErrorStream(true);
            builder.redirectOutput(temp.toFile());
            process = builder.start();
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                probeError = "ffmpeg -encoders 探测超时（" + PROBE_TIMEOUT_MS + "ms）";
                log.warn(probeError);
                return List.of();
            }
            int exit = process.exitValue();
            String text = new String(Files.readAllBytes(temp), StandardCharsets.UTF_8);
            if (exit != 0)
            {
                probeError = "ffmpeg -encoders 退出码 " + exit + "（按软件转码处理）";
                log.warn("{}，stderr/stdout 尾部: {}", probeError, tail(text));
                return List.of();
            }
            List<String> found = new ArrayList<>();
            for (String line : text.split("\\R"))
            {
                Matcher matcher = ENCODER_LINE.matcher(line);
                if (matcher.matches() && wanted.contains(matcher.group(1)) && !found.contains(matcher.group(1)))
                {
                    found.add(matcher.group(1));
                }
            }
            return found;
        }
        catch (IOException e)
        {
            probeError = "ffmpeg 探测失败: " + e.getMessage();
            log.warn(probeError);
            return List.of();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            probeError = "ffmpeg 探测被中断";
            log.warn(probeError);
            return List.of();
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                process.destroyForcibly();
            }
            if (temp != null)
            {
                try
                {
                    Files.deleteIfExists(temp);
                }
                catch (IOException e)
                {
                    // 临时文件删不掉不影响探测结果
                }
            }
        }
    }

    private static String tail(String text)
    {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(trimmed.length() - 400);
    }

    /** 配置值规范化：非法值 WARN 后按 auto（一个拼错的 yml 不该让媒体库起不来） */
    private static String normalizeRequested(String raw)
    {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || AUTO.equals(text))
        {
            return AUTO;
        }
        if (SOFTWARE.equals(text))
        {
            return MediaTranscodeCommand.SOFTWARE_ENCODER;
        }
        if (MediaTranscodeCommand.SOFTWARE_ENCODER.equals(text) || MediaTranscodeCommand.isHardware(text))
        {
            return text;
        }
        log.warn("qtools.media.transcode.encoder 配置非法：'{}'（可选 auto / software / {}），按 auto 处理", raw,
                String.join(" / ", MediaTranscodeCommand.HARDWARE_ENCODERS));
        return AUTO;
    }
}
