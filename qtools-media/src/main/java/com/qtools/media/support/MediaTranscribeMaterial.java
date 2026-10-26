package com.qtools.media.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.exception.MediaException;
import com.qtools.media.service.IMediaFileService;

/**
 * 音频转写素材（P5.57 交付 2）：ffmpeg 抽 16k mono wav → whisper-cli 转写 → 纯文本节选。
 *
 * <h2>为什么要有这条路</h2>
 *
 * <p>库里的字幕大多是机器翻的（日语语音配中文译文，甚至整段是噪声），拿它当素材只能把错误
 * 放大成梗概。用户的原话是「很多字幕根本不准，需要重新生成」——这条路不信任任何既有字幕，
 * 直接用片子自己的声音重新识别。</p>
 *
 * <h2>为什么是 CPU 而不是 CUDA</h2>
 *
 * <p>whisper.cpp 官方 release 里 x64 的 CUDA 包只到 12.4（sm_90），本机是 RTX 5060（Blackwell / sm_120），
 * 那套 kernel 跑不起来；何况 8GB 卡上 ollama 的文本 / 视觉模型已经常驻 7.5GB。
 * 选 OpenBLAS 的 CPU 包：零显存占用、不参与显存争抢，与「先描述画面再卸载视觉模型」的串行策略天然合得来。
 * 实测 ggml-small 在 8 线程上约 0.1-0.17 倍实时，够用。</p>
 *
 * <h2>为什么只转「开头 + 结尾」两段</h2>
 *
 * <p>与字幕节选同一个理由（见 {@link SubtitleExcerpt}）：几十上百分钟的片子不可能是 120s 预算内的
 * 转写对象，而梗概只需要开头（入场 / 设定）与结尾（收束）。所以按 {@code headSeconds} /
 * {@code tailSeconds} 抽两段音频分别转写，再按字符预算节选。</p>
 *
 * <h2>识别噪声的两道清洗</h2>
 *
 * <ol>
 *   <li><b>非语音标记</b>：{@code [cough]}、{@code (music)}、{@code （拍手）} 这类整段只有括号注释的片段丢掉；</li>
 *   <li><b>幻觉循环</b>：whisper 在音乐 / 空白上会死循环复读同一句（实测 90s 里同一句复读 20 次），
 *       连续重复只留一条，同一句全文最多留 {@value #REPEAT_LIMIT} 次。</li>
 * </ol>
 *
 * @author qtools
 */
@Component
public class MediaTranscribeMaterial
{
    private static final Logger log = LoggerFactory.getLogger(MediaTranscribeMaterial.class);

    /** 非语音标记：整段就是一个括号注释（[cough] / (music) / （拍手）） */
    private static final Pattern NON_SPEECH = Pattern.compile("^[\\[\\(（【<][^\\]\\)）】>]*[\\]\\)）】>]$");

    /** 同一句在全文里最多保留几次（whisper 幻觉复读的硬上限） */
    static final int REPEAT_LIMIT = 2;

    /** 语言探针的音频长度（秒） */
    static final int PROBE_SECONDS = 60;

    /** 探针拿到多少可用正文就认为语言判对了（60s 正常对白远超这个数） */
    static final int PROBE_MIN_CHARS = 20;

    /** 抽音轨 / 单次 whisper 调用的最小剩余预算：不够就没必要起了（起了也必然超时） */
    private static final long MIN_RUN_MS = 5000;

    /** 转写产物（JSON / wav）的临时目录前缀 */
    private static final String TEMP_PREFIX = "qtools-whisper-";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private IMediaFileService mediaFileService;

    @Autowired
    private MediaProperties mediaProperties;

    /** 可达性结论（能力查询与实际调用共用同一个判断，避免「接口说可用、一按就失败」） */
    public static final class Availability
    {
        public final boolean available;

        /** 实际解析到的 whisper-cli（绝对路径；不可用时为空串） */
        public final String whisperPath;

        /** 实际解析到的模型文件（绝对路径；不可用时为空串） */
        public final String modelPath;

        /** 模型名（配置原值，能力查询回包用） */
        public final String model;

        public final String reason;

        private Availability(boolean available, String whisperPath, String modelPath, String model, String reason)
        {
            this.available = available;
            this.whisperPath = whisperPath;
            this.modelPath = modelPath;
            this.model = model;
            this.reason = reason;
        }

        static Availability no(String model, String reason)
        {
            return new Availability(false, "", "", model, reason);
        }
    }

    /** 一次转写的结果（不可用时就带 reason，不抛异常：调用方按优先级回落） */
    public static final class Material
    {
        public final boolean available;

        /** 清洗后的全文（各窗口按时间顺序拼接） */
        public final String text;

        /** 节选（开头 headChars + 结尾 tailChars，复用字幕节选口径） */
        public final String excerpt;

        /** 保留的句段数 */
        public final int segments;

        /** 全文 code point 数 */
        public final int chars;

        /** whisper 判定 / 指定的语言（ja / zh / auto 判定的值） */
        public final String language;

        /** 实际转写的音频秒数（两个窗口之和） */
        public final double audioSeconds;

        /** 一共跑了多久（毫秒） */
        public final long ms;

        public final String reason;

        private Material(boolean available, String text, String excerpt, int segments, int chars, String language,
                double audioSeconds, long ms, String reason)
        {
            this.available = available;
            this.text = text;
            this.excerpt = excerpt;
            this.segments = segments;
            this.chars = chars;
            this.language = language;
            this.audioSeconds = audioSeconds;
            this.ms = ms;
            this.reason = reason;
        }

        static Material missing(String reason, long ms)
        {
            return new Material(false, "", "", 0, 0, "", 0, ms, reason);
        }
    }

    /** 一次「抽音轨 + 转写」的中间结果（两个窗口拼起来） */
    private static final class Pass
    {
        final List<SubtitleParser.Cue> cues = new ArrayList<>();

        String language = "";

        /** whisper 在 JSON 里报的语言（{@code -l auto} 时这是它的判定结果，全窗口沿用） */
        String detected = "";

        double audioSeconds = 0;

        /** 各窗口失败原因的拼接（最终 insufficient 的文案来源） */
        String reason = "";

        /** 清洗后的正文（用户可读的字数才是有意义的「素材够不够」判据） */
        List<SubtitleParser.Cue> cleaned()
        {
            return clean(cues);
        }

        /**
         * 清洗后的正文字数
         *
         * <p>必须是<b>清洗后</b>的：whisper 在音乐 / 噪声上会吐一屏 {@code [tape of paper]}、
         * {@code (音楽)}，按原始串数有几百字，按可读正文却是 0 —— 用原始字数判「够不够」会让
         * 语言重跑这一步永远不触发。</p>
         */
        int chars()
        {
            return SynopsisText.codePoints(join(cleaned()));
        }
    }

    /** 一个取音窗口：从 start 秒起取 dur 秒（dur <= 0 = 一直取到结尾） */
    private static final class Win
    {
        final double start;

        final double dur;

        Win(double start, double dur)
        {
            this.start = start;
            this.dur = dur;
        }
    }

    // ------------------------------------------------------------------ 可用性

    /** 是否可用（服务层按此决定要不要试这一路） */
    public boolean available()
    {
        return availability().available;
    }

    /**
     * 真实可用性：开关 + whisper-cli + 模型 + ffmpeg 四项都就绪才算可用
     *
     * <p>能力查询接口回的就是这份结论，所以这里每一项都要给出**具体缺哪个**的原因，
     * 而不是笼统的「不可用」。</p>
     */
    public Availability availability()
    {
        if (mediaProperties == null)
        {
            return Availability.no("", "媒体配置未加载");
        }
        MediaProperties.Transcribe cfg = mediaProperties.getLlm().getTranscribe();
        String model = cfg.getModel() == null ? "" : cfg.getModel().trim();
        if (!cfg.isEnabled())
        {
            return Availability.no(model, "音频转写未启用（qtools.media.llm.transcribe.enabled=false）");
        }
        String whisper = resolveWhisper(cfg);
        if (whisper.isEmpty())
        {
            return Availability.no(model,
                    "找不到 whisper-cli：qtools.media.llm.transcribe.whisperPath 为空，"
                            + "<home>/tools/whisper/ 下没有 " + whisperExeName() + "，PATH 里也没有");
        }
        String modelPath = resolveModel(cfg);
        if (modelPath.isEmpty())
        {
            return Availability.no(model, "找不到 whisper 模型 " + (model.isEmpty() ? "(未配置)" : model)
                    + "：qtools.media.llm.transcribe.model 应为绝对路径，或放在 <home>/data/models/whisper/ 下");
        }
        String ffmpeg = mediaProperties.getFfmpeg();
        if (ffmpeg == null || ffmpeg.trim().isEmpty())
        {
            return Availability.no(model, "未配置 ffmpeg（qtools.media.ffmpeg），无法抽取 16k 单声道音轨");
        }
        return new Availability(true, whisper, modelPath, model, "");
    }

    /**
     * 定位 whisper-cli：配置路径 → 便携包 {@code <home>/tools/whisper/} → PATH
     *
     * <p>配置了但文件不存在时<b>不回落到别处</b>：那说明用户想指定一个特定构建，
     * 静默换一个反而让人以为配置生效了。</p>
     */
    private String resolveWhisper(MediaProperties.Transcribe cfg)
    {
        String configured = cfg.getWhisperPath() == null ? "" : cfg.getWhisperPath().trim();
        if (!configured.isEmpty())
        {
            Path path = Path.of(configured);
            return Files.isRegularFile(path) ? path.toString() : "";
        }
        String home = home();
        if (!home.isEmpty())
        {
            Path portable = Path.of(home, "tools", "whisper", whisperExeName());
            if (Files.isRegularFile(portable))
            {
                return portable.toAbsolutePath().toString();
            }
        }
        return fromPath(whisperExeName());
    }

    /** 定位模型：绝对路径 → {@code <home>/data/models/whisper/} → {@code <home>/tools/whisper/models/} */
    private String resolveModel(MediaProperties.Transcribe cfg)
    {
        String model = cfg.getModel() == null ? "" : cfg.getModel().trim();
        if (model.isEmpty())
        {
            return "";
        }
        Path direct = Path.of(model);
        if (direct.isAbsolute() || model.indexOf('/') >= 0 || model.indexOf('\\') >= 0)
        {
            return Files.isRegularFile(direct) ? direct.toString() : "";
        }
        String home = home();
        if (home.isEmpty())
        {
            return "";
        }
        Path[] candidates = { Path.of(home, "data", "models", "whisper", model),
                Path.of(home, "tools", "whisper", "models", model) };
        for (Path candidate : candidates)
        {
            if (Files.isRegularFile(candidate))
            {
                return candidate.toAbsolutePath().toString();
            }
        }
        return "";
    }

    private String home()
    {
        String home = mediaProperties.getHome();
        return home == null ? "" : home.replace('\\', '/').trim();
    }

    private static String whisperExeName()
    {
        return java.io.File.separatorChar == '\\' ? "whisper-cli.exe" : "whisper-cli";
    }

    /** PATH 里按名字找（Windows 上是 {@code whisper-cli.exe}） */
    private static String fromPath(String exeName)
    {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty())
        {
            return "";
        }
        for (String dir : path.split(java.io.File.pathSeparator))
        {
            String trimmed = dir == null ? "" : dir.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            try
            {
                Path candidate = Path.of(trimmed, exeName);
                if (Files.isRegularFile(candidate))
                {
                    return candidate.toAbsolutePath().toString();
                }
            }
            catch (RuntimeException e)
            {
                // 非法 PATH 片段（含引号 / 变量）直接跳过，不影响其余目录
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 取素材

    /**
     * 取音频转写素材
     *
     * @param relPath 视频相对媒体根的路径（{@code media_video.rel_path}）
     * @param rootId  库根 id（多库根）；null = 默认根 + 多根兜底
     * @param dur     片长（秒）；null / 0 = 未知（只取开头窗口）
     */
    public Material read(String relPath, Integer rootId, BigDecimal dur)
    {
        long begin = System.currentTimeMillis();
        Availability av = availability();
        if (!av.available)
        {
            return Material.missing(av.reason, 0);
        }
        if (relPath == null || relPath.trim().isEmpty())
        {
            return Material.missing("该片没有可定位的媒体文件（rel_path 为空）", 0);
        }
        Path media;
        try
        {
            media = mediaFileService.resolveMedia(relPath, rootId);
        }
        catch (MediaException e)
        {
            return Material.missing("视频文件定位失败：" + e.getMessage(), System.currentTimeMillis() - begin);
        }
        if (media == null || !Files.isRegularFile(media))
        {
            return Material.missing("视频文件不在盘上：" + relPath, System.currentTimeMillis() - begin);
        }

        MediaProperties.Transcribe cfg = mediaProperties.getLlm().getTranscribe();
        double total = MediaFrameGrab.seconds(dur);
        List<Win> windows = windows(total, cfg.getEffectiveHeadSeconds(), cfg.getEffectiveTailSeconds());
        long deadline = begin + cfg.getEffectiveTimeoutMs();

        Path temp = null;
        try
        {
            temp = Files.createTempDirectory(TEMP_PREFIX);
            String language = chooseLanguage(av.whisperPath, av.modelPath, media, temp, total, cfg, deadline);
            Pass pass = transcribe(av.whisperPath, av.modelPath, media, windows, temp, language, cfg, deadline);
            long ms = System.currentTimeMillis() - begin;
            if (pass.cues.isEmpty())
            {
                return Material.missing(pass.reason.isEmpty() ? "whisper 没有转写出任何正文" : pass.reason, ms);
            }
            List<SubtitleParser.Cue> clean = clean(pass.cues);
            String text = join(clean);
            String excerpt = SubtitleExcerpt.of(new SubtitleParser.Doc(SubtitleParser.Format.UNKNOWN, clean),
                    mediaProperties.getLlm().getEffectiveSubtitleHeadChars(),
                    mediaProperties.getLlm().getEffectiveSubtitleTailChars());
            if (!SubtitleExcerpt.enough(excerpt))
            {
                return Material.missing("转写只有 " + SynopsisText.codePoints(excerpt) + " 字可用正文（语音太少 / 全是噪声），"
                        + "不足以概括内容", ms);
            }
            log.info("音频转写素材就绪 rel={} lang={} segments={} chars={} audio={}s ms={}", relPath, pass.language,
                    clean.size(), SynopsisText.codePoints(text), pass.audioSeconds, ms);
            return new Material(true, text, excerpt, clean.size(), SynopsisText.codePoints(text), pass.language,
                    pass.audioSeconds, ms, "");
        }
        catch (IOException e)
        {
            return Material.missing("转写临时目录创建失败：" + e.getMessage(), System.currentTimeMillis() - begin);
        }
        finally
        {
            deleteTree(temp);
        }
    }

    /**
     * 定语言：{@code auto} 判错时用 60s 探针挑一次，避免整条片子用错语言重跑
     *
     * <p>实测问题：whisper 的自动判定在音乐 / 气声 / 小声对白上经常判成八竿子打不着的语言
     * （日语 → 挪威语 / 韩语），判错后整段输出都是 {@code [tape of paper]} 这类标记词，
     * 清理完一个字都不剩。而「整条片子换语言重跑一遍」在 120s 预算里放不下（实测一次全窗口
     * 约 78s），所以改成：先在片子 1/3 处取 60s 做探针，两种语言谁的可用正文多就用谁，
     * 然后再跑一次全窗口。</p>
     *
     * <p>配置里写死语言（非 auto）时不探针：那是用户明确指定的，别自作聪明。</p>
     */
    private String chooseLanguage(String whisper, String model, Path media, Path temp, double total,
            MediaProperties.Transcribe cfg, long deadline)
    {
        String configured = cfg.getEffectiveLanguage();
        if (!"auto".equalsIgnoreCase(configured))
        {
            return configured;
        }
        String fallback = cfg.getEffectiveFallbackLanguage();
        if (fallback.isEmpty() || deadline - System.currentTimeMillis() <= MIN_RUN_MS)
        {
            return "auto";
        }
        Win probe = probeWindow(total, cfg.getEffectiveHeadSeconds());
        Pass auto = probe(whisper, model, media, temp, probe, "auto", cfg, deadline);
        // 探针拿到可用正文 → 把它的判定结果**写死**给全窗口用：
        // 逐文件重新 auto 判定会得出不同答案（同一部片子 60s 判对、300s 判错），实测同一素材
        // 探针 62 字、全窗口 30 字就是这个原因。
        if (auto.chars() >= PROBE_MIN_CHARS)
        {
            String decided = auto.detected.isEmpty() ? "auto" : auto.detected;
            log.info("语言探针：auto 判定 {} 拿到 {} 字 → 全窗口用 {}", auto.detected, auto.chars(), decided);
            return decided;
        }
        if (deadline - System.currentTimeMillis() <= MIN_RUN_MS)
        {
            return auto.detected.isEmpty() ? "auto" : auto.detected;
        }
        Pass other = probe(whisper, model, media, temp, probe, fallback, cfg, deadline);
        if (other.chars() > auto.chars())
        {
            log.info("语言探针：auto 只拿到 {} 字，{} 拿到 {} 字 → 用 {}", auto.chars(), fallback, other.chars(), fallback);
            return fallback;
        }
        log.info("语言探针：auto {} 字 / {} {} 字 → 用 {}", auto.chars(), fallback, other.chars(),
                auto.detected.isEmpty() ? "auto" : auto.detected);
        return auto.detected.isEmpty() ? "auto" : auto.detected;
    }

    /** 探针窗口：片子 1/3 处取 60s（开头常是片头 / 音乐，中间才有对白） */
    static Win probeWindow(double totalSeconds, int headSeconds)
    {
        if (totalSeconds <= PROBE_SECONDS)
        {
            return new Win(0, totalSeconds > 0 ? totalSeconds : PROBE_SECONDS);
        }
        double start = Math.max(0, Math.min(totalSeconds / 3, totalSeconds - PROBE_SECONDS));
        if (headSeconds > 0 && start + PROBE_SECONDS > headSeconds && totalSeconds > headSeconds)
        {
            // 尽量落在有意义的位置：仍在片内，只是别越界
            start = Math.min(start, Math.max(0, totalSeconds - PROBE_SECONDS));
        }
        return new Win(start, PROBE_SECONDS);
    }

    /** 单窗口探针（只关心拿到多少可用正文） */
    private Pass probe(String whisper, String model, Path media, Path temp, Win win, String language,
            MediaProperties.Transcribe cfg, long deadline)
    {
        List<Win> one = new ArrayList<>(1);
        one.add(win);
        Pass pass = transcribe(whisper, model, media, one, temp, language, cfg, deadline);
        log.debug("语言探针 lang={} chars={} reason={}", language, pass.chars(), pass.reason);
        return pass;
    }

    /** 跑一整套窗口（每个窗口：抽音轨 → whisper → 解析），预算共用同一个 deadline */
    private Pass transcribe(String whisper, String model, Path media, List<Win> windows, Path temp, String language,
            MediaProperties.Transcribe cfg, long deadline)
    {
        Pass pass = new Pass();
        pass.language = language;
        String ffmpeg = mediaProperties.getFfmpeg();
        int index = 0;
        for (Win win : windows)
        {
            long left = deadline - System.currentTimeMillis();
            if (left <= MIN_RUN_MS)
            {
                pass.reason = appendReason(pass.reason, "单条转写限时 " + cfg.getEffectiveTimeoutMs() + "ms 已用尽");
                break;
            }
            String base = "win" + (index++);
            Path wav = temp.resolve(base + ".wav");
            Run extract = exec(ffmpegCommand(ffmpeg, media, win, wav), Math.min(left, 60000), temp);
            if (!extract.ok || !Files.isRegularFile(wav) || sizeOf(wav) < 1000)
            {
                pass.reason = appendReason(pass.reason,
                        "抽音轨失败（" + (extract.timedOut ? "ffmpeg 超时" : "ffmpeg 返回 " + extract.exit + " " + tail(extract.err)) + "）");
                continue;
            }
            left = deadline - System.currentTimeMillis();
            if (left <= MIN_RUN_MS)
            {
                pass.reason = appendReason(pass.reason, "单条转写限时 " + cfg.getEffectiveTimeoutMs() + "ms 已用尽");
                break;
            }
            Run run = exec(whisperCommand(whisper, model, wav, temp.resolve(base), language,
                    cfg.getEffectiveThreads()), left, temp);
            if (run.timedOut)
            {
                pass.reason = appendReason(pass.reason, "whisper 转写超时（限时 " + cfg.getEffectiveTimeoutMs() + "ms）");
                break;
            }
            Path json = temp.resolve(base + ".json");
            if (!Files.isRegularFile(json))
            {
                pass.reason = appendReason(pass.reason, "whisper 没有产出 JSON（返回 " + run.exit + " " + tail(run.err) + "）");
                continue;
            }
            try
            {
                Parsed parsed = parse(Files.readString(json, StandardCharsets.UTF_8));
                String detected = parsed.language == null ? "" : parsed.language.trim();
                if (!detected.isEmpty() && pass.detected.isEmpty())
                {
                    pass.detected = detected;
                }
                // 报错的是「实际用的语言」，不是配置值：auto 时就是 whisper 的判定结果
                pass.language = "auto".equalsIgnoreCase(language) ? pass.detected : language;
                pass.cues.addAll(parsed.cues);
                pass.audioSeconds += win.dur > 0 ? Math.min(win.dur, parsed.seconds) : parsed.seconds;
            }
            catch (IOException e)
            {
                pass.reason = appendReason(pass.reason, "转写 JSON 解析失败：" + e.getMessage());
            }
        }
        return pass;
    }

    // ------------------------------------------------------------------ 命令构造

    /** ffmpeg：{@code -ss/-t} 输入定位 → 16k 单声道 pcm_s16le wav（whisper.cpp 只吃 wav/flac/mp3/ogg） */
    private static List<String> ffmpegCommand(String ffmpeg, Path src, Win win, Path out)
    {
        List<String> cmd = new ArrayList<>(24);
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-nostdin");
        if (win.start > 0)
        {
            cmd.add("-ss");
            cmd.add(MediaFrameGrab.ssArgOf(win.start));
        }
        if (win.dur > 0)
        {
            cmd.add("-t");
            cmd.add(MediaFrameGrab.ssArgOf(win.dur));
        }
        cmd.add("-i");
        cmd.add(src.toString());
        cmd.add("-vn");
        cmd.add("-sn");
        cmd.add("-dn");
        cmd.add("-ac");
        cmd.add("1");
        cmd.add("-ar");
        cmd.add("16000");
        cmd.add("-c:a");
        cmd.add("pcm_s16le");
        cmd.add("-f");
        cmd.add("wav");
        cmd.add("-y");
        cmd.add(out.toString());
        return cmd;
    }

    /** whisper-cli：{@code -oj} 产 JSON（{@code -of} 给前缀），{@code -np} 只留结果 */
    private static List<String> whisperCommand(String whisper, String model, Path wav, Path outBase, String language,
            int threads)
    {
        List<String> cmd = new ArrayList<>(16);
        cmd.add(whisper);
        cmd.add("-m");
        cmd.add(model);
        cmd.add("-f");
        cmd.add(wav.toString());
        cmd.add("-l");
        cmd.add(language == null || language.trim().isEmpty() ? "auto" : language.trim());
        cmd.add("-t");
        cmd.add(Integer.toString(threads));
        cmd.add("-oj");
        cmd.add("-of");
        cmd.add(outBase.toString());
        cmd.add("-np");
        return cmd;
    }

    // ------------------------------------------------------------------ 子进程

    /** 一次子进程执行的结果 */
    private static final class Run
    {
        boolean ok;

        boolean timedOut;

        int exit = -1;

        String out = "";

        String err = "";
    }

    /**
     * 同步执行子进程（stdout / stderr 都读干净，避免管道写满阻塞子进程）
     *
     * <p>超时一律 {@code destroyForcibly}：whisper 卡住时不会自己退，留着就是占满 CPU。</p>
     */
    private static Run exec(List<String> cmd, long timeoutMs, Path workDir)
    {
        Run run = new Run();
        if (cmd == null || cmd.isEmpty() || timeoutMs <= 0)
        {
            return run;
        }
        Process process = null;
        try
        {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            if (workDir != null)
            {
                builder.directory(workDir.toFile());
            }
            process = builder.start();
            final Process started = process;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread outReader = drain(started.getInputStream(), out, "qtools-whisper-out");
            Thread errReader = drain(started.getErrorStream(), err, "qtools-whisper-err");
            boolean done = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            if (!done)
            {
                run.timedOut = true;
                process.destroyForcibly();
                process.waitFor(3000, TimeUnit.MILLISECONDS);
            }
            outReader.join(2000);
            errReader.join(2000);
            run.exit = done ? process.exitValue() : -1;
            run.out = out.toString(StandardCharsets.UTF_8);
            run.err = err.toString(StandardCharsets.UTF_8);
            run.ok = done && run.exit == 0;
            return run;
        }
        catch (IOException e)
        {
            run.err = e.getMessage() == null ? "" : e.getMessage();
            return run;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return run;
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                process.destroyForcibly();
            }
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream out, String name)
    {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try
            {
                int read;
                while ((read = in.read(buffer)) >= 0)
                {
                    out.write(buffer, 0, read);
                }
            }
            catch (IOException e)
            {
                // 子进程被杀 / 管道断开：已读到的字节仍然有效
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ------------------------------------------------------------------ JSON

    /** whisper {@code -oj} 的产物：{@code {result:{language}, transcription:[{offsets:{from,to},text}]}} */
    static final class Parsed
    {
        String language = "";

        double seconds;

        final List<SubtitleParser.Cue> cues = new ArrayList<>();
    }

    static Parsed parse(String json) throws IOException
    {
        Parsed parsed = new Parsed();
        JsonNode root = JSON.readTree(json);
        parsed.language = root.path("result").path("language").asText("");
        long lastMs = 0;
        for (JsonNode segment : root.path("transcription"))
        {
            String text = segment.path("text").asText("");
            long from = segment.path("offsets").path("from").asLong(0);
            long to = segment.path("offsets").path("to").asLong(0);
            lastMs = Math.max(lastMs, to);
            parsed.cues.add(new SubtitleParser.Cue(from, to, text));
        }
        parsed.seconds = lastMs / 1000d;
        return parsed;
    }

    // ------------------------------------------------------------------ 清洗

    /** 丢非语音标记 / 连续重复 / 复读超过 {@link #REPEAT_LIMIT} 次的句子 */
    static List<SubtitleParser.Cue> clean(List<SubtitleParser.Cue> raw)
    {
        List<SubtitleParser.Cue> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (SubtitleParser.Cue cue : raw)
        {
            String text = SynopsisText.clean(cue.text);
            if (text.isEmpty() || NON_SPEECH.matcher(text).matches())
            {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).text.equals(text))
            {
                continue;
            }
            Integer count = seen.get(text);
            int next = count == null ? 1 : count + 1;
            if (next > REPEAT_LIMIT)
            {
                continue;
            }
            seen.put(text, next);
            out.add(new SubtitleParser.Cue(cue.startMs, cue.endMs, text));
        }
        return out;
    }

    private static String join(List<SubtitleParser.Cue> cues)
    {
        StringBuilder sb = new StringBuilder();
        for (SubtitleParser.Cue cue : cues)
        {
            if (sb.length() > 0)
            {
                sb.append('\n');
            }
            sb.append(cue.text);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 窗口

    /**
     * 取哪些音频窗口
     *
     * <ul>
     *   <li>片长未知 → 只取开头 {@code headSeconds}；</li>
     *   <li>{@code headSeconds <= 0} → 整条片子一次转完；</li>
     *   <li>片长 ≤ 头 + 尾 → 整条片子一次转完（不重复解码）；</li>
     *   <li>否则 → 开头 {@code headSeconds} + 结尾 {@code tailSeconds}。</li>
     * </ul>
     */
    static List<Win> windows(double totalSeconds, int headSeconds, int tailSeconds)
    {
        List<Win> out = new ArrayList<>(2);
        if (headSeconds <= 0)
        {
            out.add(new Win(0, totalSeconds > 0 ? totalSeconds : -1));
            return out;
        }
        if (totalSeconds <= 0)
        {
            out.add(new Win(0, headSeconds));
            return out;
        }
        if (totalSeconds <= headSeconds + Math.max(0, tailSeconds))
        {
            out.add(new Win(0, totalSeconds));
            return out;
        }
        out.add(new Win(0, headSeconds));
        if (tailSeconds > 0)
        {
            out.add(new Win(Math.max(0, totalSeconds - tailSeconds), tailSeconds));
        }
        return out;
    }

    // ------------------------------------------------------------------ 杂项

    private static String appendReason(String current, String add)
    {
        if (add == null || add.isEmpty())
        {
            return current == null ? "" : current;
        }
        if (current == null || current.isEmpty())
        {
            return add;
        }
        return current + "；" + add;
    }

    private static long sizeOf(Path file)
    {
        try
        {
            return Files.size(file);
        }
        catch (IOException e)
        {
            return 0;
        }
    }

    /** 子进程 stderr 尾部（日志与文案用；太长没用，whisper 只是把进度刷屏） */
    private static String tail(String text)
    {
        if (text == null)
        {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 160 ? flat : flat.substring(flat.length() - 160);
    }

    private static void deleteTree(Path dir)
    {
        if (dir == null)
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException e)
                {
                    // 临时文件删不掉不影响结论（系统临时目录会自己清）
                }
            });
        }
        catch (IOException e)
        {
            log.debug("转写临时目录清理失败 dir={} err={}", dir, e.getMessage());
        }
    }
}
