package com.qtools.media.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 抽帧（P5.53 视觉摘要的取景一步）：一条视频取 4-6 帧，缩到 512 宽 JPEG。
 *
 * <h2>为什么直接读 ffmpeg 的 stdout</h2>
 *
 * <p>{@code -f mjpeg pipe:1} 不落临时文件：512 宽单帧 30-60KB，5 帧也就 300KB，
 * 全程在内存里，用完即弃。写盘再读回只会多两个失败点（临时目录权限、残留文件）。</p>
 *
 * <h2>取哪几帧</h2>
 *
 * <p>按片长百分比<b>均匀取样</b>：10% / 30% / 50% / 70% / 90%（{@code frames=5} 时）。
 * 为什么不用「每秒一帧再挑」：那是几百次解码，而作品简介要的只是「这是什么片」，
 * 5 帧覆盖开头（入场）、中段（主体）、结尾（收束）就够判断体裁与内容了。</p>
 *
 * <p>末端修正与 {@link MediaFrameCommand#secondOf} 同口径：{@code -ss t >= dur} 时 ffmpeg 不出帧，
 * 所以夹到 {@code dur - 1}。</p>
 *
 * @author qtools
 */
public final class MediaFrameGrab
{
    /** JPEG 质量（2 最好 / 31 最差）：5 在 512 宽下约 30-60KB，够看清人物与场景 */
    public static final int JPEG_QUALITY = 5;

    /** 单帧解码超时（毫秒）：输入定位后只解码 1 帧，正常 &lt; 1s；卡住就是文件坏了 */
    private static final int FRAME_TIMEOUT_MS = 15000;

    private MediaFrameGrab()
    {
    }

    /**
     * 构造取帧命令（输出 mjpeg 到 stdout）
     *
     * <p>与 {@link MediaFrameCommand} 的区别只在宽度可变与质量参数：那条路是给浏览器 hover 用的
     * （480 固定宽、走缓存），这条给视觉模型用（宽度可配、不缓存）。</p>
     */
    public static List<String> build(String ffmpeg, Path src, String ssArg, int width)
    {
        List<String> cmd = new ArrayList<>(20);
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-nostdin");
        cmd.add("-ss");
        cmd.add(ssArg == null || ssArg.trim().isEmpty() ? "0" : ssArg.trim());
        cmd.add("-i");
        cmd.add(src.toString());
        cmd.add("-frames:v");
        cmd.add("1");
        cmd.add("-an");
        cmd.add("-sn");
        cmd.add("-vf");
        cmd.add("scale=" + width + ":-2");
        cmd.add("-q:v");
        cmd.add(Integer.toString(JPEG_QUALITY));
        cmd.add("-f");
        cmd.add("mjpeg");
        cmd.add("pipe:1");
        return cmd;
    }

    /**
     * 均匀取样的时刻（秒）：{@code frames=5, dur=100} → 10 / 30 / 50 / 70 / 90
     *
     * <p>首帧不用 0%：很多片子的第 0 秒是黑场 / 片头 logo，取不到内容。</p>
     */
    public static List<Integer> sampleSeconds(double dur, int frames)
    {
        List<Integer> out = new ArrayList<>();
        if (frames <= 0)
        {
            return out;
        }
        if (dur <= 0)
        {
            // 时长未知：退化成「都取第 1 秒」，至少能拿到一帧
            out.add(1);
            return out;
        }
        int last = Math.max(1, (int) Math.floor(dur) - 1);
        for (int i = 0; i < frames; i++)
        {
            double ratio = frames == 1 ? 0.5 : 0.1 + 0.8 * i / (frames - 1);
            int sec = (int) Math.round(dur * ratio);
            sec = Math.max(1, Math.min(sec, last));
            if (!out.contains(sec))
            {
                out.add(sec);
            }
        }
        return out;
    }

    /** 秒 → {@code -ss} 实参（与 {@link MediaFrameCommand#ssArgOf} 同口径） */
    public static String ssArgOf(double sec)
    {
        double s = Math.max(0, Math.round(sec * 1000d) / 1000d);
        if (s == Math.rint(s))
        {
            return Long.toString((long) s);
        }
        return BigDecimal.valueOf(s).stripTrailingZeros().toPlainString();
    }

    /**
     * 抓一帧（同步；返回 JPEG 字节）
     *
     * @return JPEG 字节；ffmpeg 不可用 / 超时 / 出空 → 空数组（调用方按「这一帧没抽到」处理，
     *         绝不因为一帧失败就整条放弃）
     */
    public static byte[] grab(String ffmpeg, Path src, double sec, int width)
    {
        if (ffmpeg == null || ffmpeg.trim().isEmpty() || src == null)
        {
            return new byte[0];
        }
        List<String> cmd = build(ffmpeg, src, ssArgOf(sec), width);
        Process process = null;
        try
        {
            process = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            final Process started = process;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copy(started.getInputStream(), out), "media-frame-read");
            reader.setDaemon(true);
            reader.start();
            boolean done = process.waitFor(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done)
            {
                process.destroyForcibly();
                return new byte[0];
            }
            reader.join(2000);
            byte[] bytes = out.toByteArray();
            return bytes.length > 100 ? bytes : new byte[0];
        }
        catch (IOException e)
        {
            return new byte[0];
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new byte[0];
        }
        finally
        {
            if (process != null && process.isAlive())
            {
                process.destroyForcibly();
            }
        }
    }

    /** 把 ffmpeg 的 stderr 读干净（不读会阻塞子进程），只保留尾部用于日志 */
    public static String drainError(Process process)
    {
        if (process == null)
        {
            return "";
        }
        try
        {
            return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        catch (IOException e)
        {
            return "";
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out)
    {
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
            // 子进程被杀 / 管道断开：已经读到的字节仍然有效
        }
    }

    /** 秒 → 整秒（{@link #sampleSeconds} 的入参归一化） */
    public static double seconds(BigDecimal dur)
    {
        if (dur == null || dur.signum() <= 0)
        {
            return 0;
        }
        return dur.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
