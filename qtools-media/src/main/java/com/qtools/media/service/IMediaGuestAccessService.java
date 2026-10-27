package com.qtools.media.service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 未登录可见性（P5.78）—— 全站唯一的「这个请求算不算未登录」+「这个资源在不在收紧的根里」判定
 *
 * <p>背景：{@code media_root.guest_visible = 0} 的根（管理端「库根管理」页的行内开关）
 * 表示「仅登录可见」，未登录访客既不该在索引里看到它，也不该能直接访问它的资源。</p>
 *
 * <p><b>为什么集中在一个服务里</b>：判定要落到三类资源（视频 / 相册 / 图片）× 两种访问形态
 * （索引聚合 / 按 id 或 hash 直接取），如果每个接口各写一个 {@code if}，漏一处就是一个越权洞。
 * 因此这里只暴露两类能力：</p>
 * <ol>
 *   <li><b>筛</b>：{@link #hiddenRootIds()} / {@link #isHidden(Integer)} —— 索引构建时整行跳过；</li>
 *   <li><b>拦</b>：{@link #isBlocked(HttpServletRequest, String, Map)} ——
 *       {@code MediaGuestInterceptor} 在进入 controller 之前统一判 404。</li>
 * </ol>
 *
 * <p>登录态口径复用 P5.74：{@code Authorization: Bearer <app token>} 校验通过 = 登录，
 * 其余（缺 token / 非 Bearer / 失效 / 校验组件不可用 / 校验抛异常）= 未登录。
 * 无效 token 一律按未登录处理，媒体站匿名必须永远可用。</p>
 *
 * <p>缓存：根清单 / 隐藏根集合 / 根的 realpath 全部缓存 {@link #TTL_MS} 毫秒，管理端改了
 * {@code guest_visible} 会立刻 {@link #invalidateRoots()}。这样每个请求的额外代价是
 * 一次点查（id → root_id），不是一次全表读。</p>
 *
 * @author qtools
 */
public interface IMediaGuestAccessService
{
    /** 根清单缓存时长（毫秒）：单靠 TTL 兜底，正常情况下由管理端写库后的 invalidate 提前失效 */
    long TTL_MS = 5000L;

    /** 本次请求是否未登录（无有效 app token） */
    boolean isGuest(HttpServletRequest request);

    /** 当前线程正在处理的请求是否未登录（拿不到请求上下文时按「已登录」处理，即不收紧） */
    boolean isGuest();

    /** {@code guest_visible = 0} 的库根 id 集合（恒非 null；空集合 = 没有收紧的根） */
    Set<Integer> hiddenRootIds();

    /** 这个根是否对未登录访客隐藏；{@code rootId} 为 null（存量行 / 配置默认根无对应行）时返回 false */
    boolean isHidden(Integer rootId);

    /** 根列表被改过（{@code guest_visible} 等）：清掉本类的缓存，下一次请求立即按新值判定 */
    void invalidateRoots();

    /**
     * 绝对路径属于哪个 {@code media_root} 行
     *
     * <p>与 {@code IMediaFileService#rootIdOf(Path)} 的区别：那个把「配置 {@code qtools.media.rootPath}
     * 之下」一律返回 null（因为存量行的 {@code root_id} 是 NULL），而这里要返回<b>行 id</b>——
     * 默认根那一行同样可以在管理端被设成「仅登录可见」。</p>
     *
     * @return 匹配到的行 id；不在任何已登记的根下时返回 null（不收紧）
     */
    Integer rootIdOfPath(Path absolutePath);

    /**
     * 拦截器的唯一判定入口：这次请求是否必须在进入 controller 之前回 404
     *
     * @param request 当前请求
     * @param pattern Spring 匹配到的路由模板（{@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}）
     * @param vars    路由模板变量（{@code id} / {@code albumId} / {@code i} 等）
     * @return true = 立即 404（资源在收紧的根下）；false = 放行
     */
    boolean isBlocked(HttpServletRequest request, String pattern, Map<String, String> vars);

    /** 未登录且该根收紧时抛 404（服务层自查用：不经过拦截器的读路径可调它） */
    void requireVisible(Integer rootId);
}
