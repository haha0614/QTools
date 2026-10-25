package com.qtools.media.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 实时转码的 ffmpeg 命令行构造（软/硬编码器的<b>唯一</b>分叉点）
 *
 * <p>抽成独立类而不是留在 {@code MediaStreamServiceImpl} 里，是因为这一串参数的每一段都要能被
 * <b>离线逐字核验</b>：{@code MediaTranscodeCommandTest}（doc 里描述的探针）直接调 {@link #build}
 * 打印三种配置下的完整命令，再拿它去真跑本机 ffmpeg。命令一旦埋在 Service 的私有方法里，
 * 就只能靠读代码断言，验证不了「nvnec 那串参数 ffmpeg 真的认」。</p>
 *
 * <p><b>不变的公共段</b>（h264 与硬件编码器完全一致，改动会破坏前端播放）：</p>
 * <ul>
 *   <li>{@code -ss} 在 {@code -i} 之前（输入侧快速 seek）；</li>
 *   <li>{@code scale=w='min(iw,N)':h='min(ih,N)'}：小片源不放大 + yuv420p 偶数对齐；</li>
 *   <li>音频 {@code aac} 码率、{@code frag_keyframe+empty_moov+default_base_moof} 分片 MP4、{@code pipe:1}。</li>
 * </ul>
 *
 * <p><b>变化段</b>（只换视频编码器那一小段）：</p>
 * <table border="1">
 *   <caption>编码器 → 视频参数</caption>
 *   <tr><th>编码器</th><th>参数</th></tr>
 *   <tr><td>{@code libx264}（软件）</td>
 *       <td>{@code -c:v libx264 -preset veryfast -tune zerolatency} + {@code -keyint_min 48 -sc_threshold 0}
 *           （x264 私有选项，硬件编码器不认，必须只在软件分支出现）</td></tr>
 *   <tr><td>{@code h264_nvenc} / {@code hevc_nvenc} / {@code av1_nvenc}</td>
 *       <td>{@code -c:v <enc> -preset <p4> -rc vbr -cq <n>}</td></tr>
 *   <tr><td>{@code h264_qsv} / {@code hevc_qsv}</td>
 *       <td>{@code -c:v <enc> -preset veryfast -global_quality <n>}</td></tr>
 * </table>
 *
 * <p>未知编码器名一律按软件分支构造（配置校验在 {@code MediaTranscodeProbe} 里做，
 * 这里只保证「绝不拼出一条 ffmpeg 根本不认的命令」）。</p>
 *
 * @author qtools
 */
public final class MediaTranscodeCommand
{
    /** 软件编码器（x264）；回退与探测链的兜底 */
    public static final String SOFTWARE_ENCODER = "libx264";

    /** nvenc 默认 preset（p1 最快 / p7 最高质量） */
    public static final String DEFAULT_NVENC_PRESET = "p4";

    /** 硬件编码器名单：探测链、回退判定、describe 出参共用 */
    public static final List<String> HARDWARE_ENCODERS = List.of(
            "h264_nvenc", "hevc_nvenc", "av1_nvenc", "h264_qsv", "hevc_qsv");

    /** nvenc 系：用 -rc vbr -cq 表达「质量优先 + 码率上限」 */
    private static final List<String> NVENC = List.of("h264_nvenc", "hevc_nvenc", "av1_nvenc");

    /** qsv 系：用 -global_quality 表达恒定质量 */
    private static final List<String> QSV = List.of("h264_qsv", "hevc_qsv");

    private MediaTranscodeCommand()
    {
    }

    /** 是否硬件编码器（决定失败要不要回退软件、要不要在 describe 里标 hardware） */
    public static boolean isHardware(String encoder)
    {
        return encoder != null && HARDWARE_ENCODERS.contains(encoder.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 构造一条完整的 ffmpeg 命令
     *
     * @param ffmpeg      ffmpeg 可执行文件路径（{@code qtools.media.ffmpeg}）
     * @param encoder     视频编码器名（软件 {@link #SOFTWARE_ENCODER} 或硬件编码器）
     * @param nvencPreset nvenc 的 {@code -preset}（p1-p7）；qsv 固定 veryfast，软件固定 veryfast
     * @param cq          恒定质量因子（nvenc {@code -cq} / qsv {@code -global_quality}；软件仍用 -crf 语义外的码率控制）
     * @param profile     清晰度档位（尺寸与三档码率）
     * @param file        输入文件绝对路径
     * @param start       起播秒数（&gt;= 0）
     * @return 参数列表（{@code ProcessBuilder} 原样使用，不经过 shell）
     */
    public static List<String> build(String ffmpeg, String encoder, String nvencPreset, int cq, Profile profile,
            Path file, int start)
    {
        String enc = normalizeEncoder(encoder);
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        // -ss 放在 -i 之前：输入侧快速 seek，避免从头解码
        command.add("-ss");
        command.add(String.valueOf(Math.max(0, start)));
        command.add("-i");
        command.add(file.toString());
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a:0?");
        command.add("-sn");
        command.add("-dn");
        command.add("-vf");
        command.add(scaleFilter(profile));

        // ---- 视频编码块：硬件 / 软件唯一的分叉点 ----
        command.add("-c:v");
        command.add(enc);
        if (NVENC.contains(enc))
        {
            command.add("-preset");
            command.add(nvencPreset == null || nvencPreset.trim().isEmpty()
                    ? DEFAULT_NVENC_PRESET : nvencPreset.trim());
            command.add("-rc");
            command.add("vbr");
            command.add("-cq");
            command.add(String.valueOf(clampCq(cq)));
        }
        else if (QSV.contains(enc))
        {
            command.add("-preset");
            command.add("veryfast");
            command.add("-global_quality");
            command.add(String.valueOf(clampCq(cq)));
        }
        else
        {
            // 软件：保持 P5 之前的老参数（veryfast + zerolatency，直播优先）
            command.add("-preset");
            command.add("veryfast");
            command.add("-tune");
            command.add("zerolatency");
        }

        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-b:v");
        command.add(profile.videoBitrate);
        command.add("-maxrate");
        command.add(profile.maxRate);
        command.add("-bufsize");
        command.add(profile.bufSize);
        command.add("-g");
        command.add("48");
        if (!isHardware(enc))
        {
            // x264 私有选项：硬件编码器没有 keyint_min / sc_threshold，带着会被 ffmpeg 判为未知选项
            command.add("-keyint_min");
            command.add("48");
            command.add("-sc_threshold");
            command.add("0");
        }
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(profile.audioBitrate);
        command.add("-movflags");
        command.add("frag_keyframe+empty_moov+default_base_moof");
        command.add("-f");
        command.add("mp4");
        command.add("pipe:1");
        return command;
    }

    /** min(iw,…) 保证小片源不放大；force_divisible_by=2 满足 yuv420p 偶数尺寸 */
    public static String scaleFilter(Profile profile)
    {
        return "scale=w='min(iw," + profile.width + ")':h='min(ih," + profile.height
                + ")':force_original_aspect_ratio=decrease:force_divisible_by=2";
    }

    /** 未知 / 空编码器名按软件构造（探测层已经校验过配置，这里只是不让自己拼出非法命令） */
    private static String normalizeEncoder(String encoder)
    {
        String text = encoder == null ? "" : encoder.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty())
        {
            return SOFTWARE_ENCODER;
        }
        if ("software".equals(text))
        {
            return SOFTWARE_ENCODER;
        }
        if (SOFTWARE_ENCODER.equals(text) || isHardware(text))
        {
            return text;
        }
        return SOFTWARE_ENCODER;
    }

    /** cq 合理区间 1-51（h264 的 QP 上限），越界取边界而不是让 ffmpeg 报错 */
    private static int clampCq(int cq)
    {
        if (cq < 1)
        {
            return 1;
        }
        return Math.min(cq, 51);
    }

    /** 单一清晰度档位的转码参数（原 {@code MediaStreamServiceImpl.Preset}，抽出以便离线探针复用） */
    public static final class Profile
    {
        /** 缩放上限宽度 */
        public final int width;

        /** 缩放上限高度 */
        public final int height;

        /** 目标视频码率 */
        public final String videoBitrate;

        /** 峰值视频码率 */
        public final String maxRate;

        /** 码率缓冲 */
        public final String bufSize;

        /** 音频码率 */
        public final String audioBitrate;

        public Profile(int width, int height, String videoBitrate, String maxRate, String bufSize,
                String audioBitrate)
        {
            this.width = width;
            this.height = height;
            this.videoBitrate = videoBitrate;
            this.maxRate = maxRate;
            this.bufSize = bufSize;
            this.audioBitrate = audioBitrate;
        }
    }
}
