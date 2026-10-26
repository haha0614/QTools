package com.qtools.media.service;

/**
 * 视频实时取帧 服务层（P5.15）
 *
 * <p>背景：精灵图单图放不下 7200 格（2 小时片要 &lt;1 秒精度就要 7200 格），多图方案磁盘 1-2GB
 * 且生成 10 小时 —— 所以 hover 精确帧改成<b>按需实时取帧</b>，取到的帧按整秒永久落盘。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>缓存位置 {@code <thumbs>/frames/<content_hash|videoId>/<秒>.jpg}，命中直接读盘，
 *       <b>不启动 ffmpeg</b>；同一秒的请求因此不会重复解码；</li>
 *   <li>取帧失败（无 ffmpeg / 解码失败）只抛 503 / 404，绝不 500 —— 前端据此静默回退精灵图；</li>
 *   <li>并发上限由 {@code qtools.media.maxFrame}（默认 4）控制，超限 429。</li>
 * </ul>
 *
 * @author qtools
 */
public interface IMediaFrameService
{
    /** ffmpeg 是否可用（{@code qtools.media.ffmpeg} 已配置且文件存在） */
    boolean isAvailable();

    /**
     * 视频时长（秒），用于把 {@code t} 校验到 [0, dur]
     *
     * @return 时长整秒；视频不存在 / 时长未知返回 -1
     */
    int durationOf(String videoId);

    /**
     * 取某一整秒的帧（jpeg 字节）
     *
     * @param videoId 视频 id（{@code sha1(rel)[:12]}）
     * @param sec     目标整秒（调用方已夹到 [0, dur]）
     * @return jpeg 字节（非空）
     * @throws com.qtools.media.exception.MediaException 403 id 非法 / 404 无此帧 / 429 取帧并发超限 /
     *         503 ffmpeg 不可用
     */
    byte[] frame(String videoId, int sec);

    /**
     * 取**精确时刻**的帧（jpeg 字节，P5.18）
     *
     * <p>与 {@link #frame(String, int)} 的两点差别：</p>
     * <ol>
     *   <li>{@code -ss} 带小数，落点就是请求的那一刻（不是「这一秒的第一帧」）；</li>
     *   <li><b>不落盘缓存</b>：精确落点每次都可能不同，落盘就是无上限的磁盘增长。
     *       调用方只有「换流前那张覆盖图」，一次 seek 一张，量级与 seek 次数同级。</li>
     * </ol>
     *
     * @param videoId 视频 id（{@code sha1(rel)[:12]}）
     * @param sec     目标时刻（秒，可含小数；负数按 0 处理）
     * @return jpeg 字节（非空）
     * @throws com.qtools.media.exception.MediaException 403 / 404 / 429 / 503（与 frame 同口径）
     */
    byte[] frameAt(String videoId, double sec);

    /**
     * 取**精确时刻**的帧，并指定输出宽度（P5.20）
     *
     * <p>宽度是为「换流覆盖层铺满舞台」准备的：那个场景下 480 宽放大就是低清，
     * 前端按舞台 CSS 宽度要 960-1280。其余语义（不落缓存 / 并发名额 / 失败码）与
     * {@link #frameAt(String, double)} 完全一致。</p>
     *
     * @param width 输出宽度；{@code <= 0} 退回默认 480，越界夹到 [480, 1280]
     */
    byte[] frameAt(String videoId, double sec, int width);
}
