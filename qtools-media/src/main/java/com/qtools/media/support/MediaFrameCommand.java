package com.qtools.media.support;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时取帧（hover 精确帧）的 ffmpeg 命令行构造 —— <b>唯一</b>分叉点
 *
 * <p>与 {@link MediaTranscodeCommand} 同一个理由：命令一旦埋进 Service 私有方法里，就只剩
 * 「读代码相信它」这一种验证方式。集中在这里之后可以离线把命令逐字打印出来，
 * 再拿它去真跑本机 ffmpeg 比对（P5.15 的实测就是这么做的）。</p>
 *
 * <h2>为什么是 {@code -ss} 在 {@code -i} 之前</h2>
 *
 * <p>放在输入前 = <b>输入定位</b>：ffmpeg 先跳到目标时刻之前最近的关键帧，再只解码到目标时刻，
 * 拿到 1 帧就退出。放在输出后（输出定位）会把整片从头解码到目标位置 —— 2 小时的片子取 t=3600
 * 要几十秒，正是 P5.15 明确要避免的做法。</p>
 *
 * <p>输出走 {@code -f mjpeg pipe:1}：不落临时中间文件，字节直接进内存（480 宽单帧约 20-60KB）。</p>
 *
 * @author qtools
 */
public final class MediaFrameCommand
{
    /** 输出宽度（高度用 {@code -2} 自适应并保证偶数，避免奇数高度编码失败） */
    public static final int FRAME_WIDTH = 480;

    /** mjpeg 质量（2 = 最好 / 31 = 最差）；5 在 480 宽下约 20-60KB，够看清画面 */
    public static final int JPEG_QUALITY = 5;

    /**
     * 请求宽度下限（P5.20）：低于 480 的画面铺满舞台已经明显糊了，没有使用场景
     */
    public static final int MIN_WIDTH = 480;

    /**
     * 请求宽度上限（P5.20）：换流覆盖层要铺满整个舞台，1280 与常见舞台 CSS 宽度同一量级；
     * 再大只是多花 ffmpeg 时间与带宽，画质提升肉眼不可见
     */
    public static final int MAX_WIDTH = 1280;

    private MediaFrameCommand()
    {
    }

    /**
     * 构造取帧命令
     *
     * @param ffmpeg ffmpeg 可执行文件路径（{@code qtools.media.ffmpeg}）
     * @param src    源视频（已过 resolveMedia 校验的真实路径）
     * @param sec    目标整秒（调用方已夹到 [0, dur]）
     * @return 可直接交给 ProcessBuilder 的参数表（不含 shell 引号）
     */
    public static List<String> build(String ffmpeg, Path src, int sec)
    {
        return build(ffmpeg, src, Integer.toString(Math.max(0, sec)));
    }

    /**
     * 构造取帧命令（P5.18：{@code -ss} 允许小数）
     *
     * <p>为什么需要小数：换流前盖在画面上的那张帧必须就是**用户点的那个位置**。整秒取帧给的是
     * 「这一秒的第一帧」，用户会先看到整秒位置、再看到真正落点（「先跳到整秒位置再跳到点击位置」）。
     * ffmpeg 的输入定位（{@code -ss} 在 {@code -i} 之前）默认 accurate_seek，小数秒会被精确定位到帧。</p>
     *
     * @param ssArg {@code -ss} 的实参（整数形态与旧版逐字一致："3" / "3.4" / "6.267"）
     */
    public static List<String> build(String ffmpeg, Path src, String ssArg)
    {
        return build(ffmpeg, src, ssArg, FRAME_WIDTH);
    }

    /**
     * 构造取帧命令（P5.20：输出宽度可指定）
     *
     * <p>为什么要宽度：同一个接口有两种用途 —— hover / 拖动跟随层只要 160×90 的卡片（480 足够），
     * 而换流覆盖层要铺满整个舞台，480 宽放大上去就是用户看到的「低清」。
     * 前端按舞台 CSS 宽度要 960-1280，这里只做区间夹取，不信任调用方。</p>
     *
     * @param width 输出宽度（{@link #clampWidth(int)} 夹到 [480, 1280]）
     */
    public static List<String> build(String ffmpeg, Path src, String ssArg, int width)
    {
        List<String> cmd = new ArrayList<>(20);
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        // 服务端进程的 stdin 不是终端：不加 -nostdin 时 ffmpeg 可能读走字节并卡住
        cmd.add("-nostdin");
        // 关键帧定位：-ss 必须在 -i 之前（输入定位），见类注释
        cmd.add("-ss");
        cmd.add(ssArg == null || ssArg.trim().isEmpty() ? "0" : ssArg.trim());
        cmd.add("-i");
        cmd.add(src.toString());
        cmd.add("-frames:v");
        cmd.add("1");
        // 只要画面：不取音轨 / 字幕，少一路解码就少一份延迟
        cmd.add("-an");
        cmd.add("-sn");
        cmd.add("-vf");
        cmd.add("scale=" + clampWidth(width) + ":-2");
        cmd.add("-q:v");
        cmd.add(Integer.toString(JPEG_QUALITY));
        cmd.add("-f");
        cmd.add("mjpeg");
        cmd.add("pipe:1");
        return cmd;
    }

    /**
     * 请求宽度 → 实际输出宽度：{@code <= 0} 退回默认 480，其余夹到 [{@link #MIN_WIDTH}, {@link #MAX_WIDTH}]
     *
     * <p>{@code <= 0} 才是「没传」，越界的正数则夹取而不是报错 —— 宽度是画质偏好，不是查询条件，
     * 拒绝请求对调用方没有好处（与 t 越界必须 400 的口径不同）。</p>
     */
    public static int clampWidth(int width)
    {
        if (width <= 0)
        {
            return FRAME_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    /**
     * 请求时刻 → {@code -ss} 的实参（P5.18：保留到毫秒，整数形态与旧版逐字一致）
     *
     * <p>与 {@link #secondOf} 的分工：整秒取帧（hover / 拖动跟随层）走 {@code secondOf} + 落盘缓存，
     * 只有「精确落点」（换流覆盖层）才用这里的小数形态，且不落缓存。</p>
     *
     * @param t 请求时刻（秒）
     * @return "3"（整数）/ "3.4" / "6.267"（毫秒级，半帧 16.7ms @30fps 远大于 1ms）
     */
    public static String ssArgOf(double t)
    {
        double s = Math.max(0, Math.round(t * 1000d) / 1000d);
        if (s == Math.rint(s))
        {
            return Long.toString((long) s);
        }
        return BigDecimal.valueOf(s).stripTrailingZeros().toPlainString();
    }

    /**
     * 请求时刻 → 缓存键整秒
     *
     * <p>缓存目录按「整秒」分文件（{@code <hash>/<秒>.jpg}），所以这里的取整规则就是缓存语义：
     * 同一秒内的所有 hover（3600.0 / 3600.4 / 3600.9）都命中同一个文件，单帧只生成一次。</p>
     *
     * <p>末端修正：{@code t == dur} 时 {@code -ss dur} 已经越过最后一帧，ffmpeg 不出片，
     * 因此夹到 {@code dur - 1}（该秒的内容本来就在片尾附近，视觉上无差别）。</p>
     *
     * @param t      请求时刻（秒）
     * @param durSec 片长（秒）；&lt;= 0 表示未知，不做末端修正
     * @return 非负整秒
     */
    public static int secondOf(double t, int durSec)
    {
        int sec = (int) Math.floor(Math.max(0, t));
        if (durSec > 0 && sec >= durSec)
        {
            sec = Math.max(0, durSec - 1);
        }
        return sec;
    }
}
