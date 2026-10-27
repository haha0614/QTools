package com.qtools.media.service;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * 人物卡背景「服务端预渲染 + 内容寻址缓存」服务（P5.90）
 *
 * <p>用户口径：「在我编辑后把我所选的区域裁下来作为一个文件或缓存，然后每次加载就直接加载这个图片就行了，
 * 不用每次都计算了」—— 前端不再挂三层图腾挪 CSS 变量，改成一个 {@code <img>} 指向本接口。</p>
 *
 * <p>缓存文件名 {@code <thumbs>/cardbg/<personId>-<w>x<h>-<paramsHash>.jpg}，{@code paramsHash} 覆盖
 * 「原图标识（路径 + size + mtime / 取帧视频 id + 秒）+ scale + dx + dy + blur + 主题 + 卡片底色 + w + h」，
 * 因此<b>内容寻址</b>：任何输入变化都会换文件名，旧文件不会被误用；文件名不变 = 字节不变，
 * 响应可以放心给 {@code immutable} 长缓存。</p>
 *
 * @author qtools
 */
public interface IMediaPersonCardBgService
{
    /**
     * 渲染（或命中缓存返回）一张人物卡背景
     *
     * @param personId 人物 id
     * @param w        输出宽（已由控制器校验 1–2000；本方法再夹到 1200 上限内）
     * @param h        输出高（同上，上限 600）
     * @param scale    缩放倍率（null = 用人物已保存的 bg_scale；0.5–3）
     * @param dx       水平偏移（null = 用 bg_dx；卡片宽高的比例，±2）
     * @param dy       垂直偏移（null = 用 bg_dy）
     * @param blur     模糊半径（0–20px；null = 6）
     * @param dark     是否暗色主题（只影响柔光的混合目标色）
     * @param bgHex    卡片底色 --bg-2（{@code RRGGBB}；null/非法 = ffffff）
     * @return 渲染结果（字节 + 缓存键 + 是否命中 + 耗时）
     * @throws com.qtools.media.exception.MediaException 404 人物 / 头像来源不存在、503 取帧不可用 / 缩略图未配置
     */
    CardBgImage cardBg(Long personId, Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy,
            Integer blur, boolean dark, String bgHex);

    /**
     * 后台预热（P5.92）：把这些人「上一次真实访问用的那组参数」重新渲染一遍，已有的直接跳过
     *
     * <p>为什么用「上一次访问的参数」而不是一组写死的参数：尺寸 / 模糊 / 主题 / 卡片底色全都进内容哈希，
     * 猜不出来 —— 320px 宽的窗口和 1600px 的窗口要的是两张不同的图。所以每次 {@link #cardBg} 都记下
     * 这个人最近一次的参数与缓存文件路径，预热就按它来：文件在 → 跳过；文件没了（换头像 / 清过缓存）→ 重渲染。</p>
     *
     * <p>异步：丢进一个有界队列，2–4 个后台线程串行消费，调用方（索引构建 / 保存参数）立刻返回。
     * 队列满了就丢（宁可这次不预热，也不要让保存动作卡住）。</p>
     *
     * @param personIds 人物 id；null / 空 = 直接返回
     */
    void warmAsync(Collection<Long> personIds);

    /**
     * 站长改完背景参数后的预热：照旧用上次访问的尺寸 / 模糊 / 主题，但缩放与偏移**改用库里刚存的值**
     *
     * <p>为什么必须清掉记录里的 s/dx/dy：那三个是「保存前」的值，拿它预热会渲出一张用户马上就不要的图。
     * 传 null 让服务层回落到人物已保存的 bg_scale/bg_dx/bg_dy —— 与前端保存后立刻发出的请求同一个内容哈希。</p>
     *
     * @param personIds 人物 id
     */
    void warmAfterEdit(Collection<Long> personIds);

    /**
     * 同步预热一个人（管理端接口 / 测试用）
     *
     * @param personId 人物 id
     * @param w        输出宽（null = 用上次访问的 / 缺省 666）
     * @param h        输出高（null = 用上次访问的 / 缺省 204）
     * @param scale    缩放（null = 用上次访问的 / 人物已保存值）
     * @param dx       水平偏移（同上）
     * @param dy       垂直偏移（同上）
     * @param blur     模糊半径（null = 上次访问的 / 6）
     * @param dark     暗色主题（照抄前端；调用方没给就沿用上次访问的）
     * @param bgHex    卡片底色 {@code RRGGBB}（null = 上次访问的 / ffffff）
     * @return 渲染结果（{@link CardBgImage#isHit()} = true 表示本来就有，没白渲染）
     */
    CardBgImage warmOne(Long personId, Integer w, Integer h, BigDecimal scale, BigDecimal dx, BigDecimal dy,
            Integer blur, boolean dark, String bgHex);

    /**
     * 预热状态（排障 / 实测用）
     *
     * @return {@code {queued, threads, rendered, skipped, failed, enqueued, persons, specs}}
     */
    java.util.Map<String, Object> warmStats();

    /** 渲染结果 */
    final class CardBgImage
    {
        private final byte[] bytes;

        private final String key;

        private final boolean hit;

        private final long renderMs;

        private final int width;

        private final int height;

        public CardBgImage(byte[] bytes, String key, boolean hit, long renderMs, int width, int height)
        {
            this.bytes = bytes;
            this.key = key;
            this.hit = hit;
            this.renderMs = renderMs;
            this.width = width;
            this.height = height;
        }

        public byte[] getBytes()
        {
            return bytes;
        }

        /** 内容哈希（20 位十六进制）：ETag / 排障用 */
        public String getKey()
        {
            return key;
        }

        /** true = 直接读的缓存文件（未渲染） */
        public boolean isHit()
        {
            return hit;
        }

        /** 命中时是「读盘 + 校验」耗时，未命中是「渲染 + 编码 + 落盘」耗时 */
        public long getRenderMs()
        {
            return renderMs;
        }

        public int getWidth()
        {
            return width;
        }

        public int getHeight()
        {
            return height;
        }
    }
}
