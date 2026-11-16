package com.qtools.media.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * 人物卡封面（P6.101）：四个视图各自的图源与几何
 *
 * <p>取值优先级：{@code user(视图)} → {@code default(视图)} → 自动（沿用 {@code avatarOf} 的三档回退）。
 * 站端回包里每个视图一个节点 {@code {kind, ref, scale, dx, dy, source}}，{@code source} 标明这一份
 * 来自 {@code user} 还是 {@code default} —— 前端据此决定「重置」按钮是否可用、要不要提示「用的是默认值」。</p>
 *
 * @author qtools
 */
public interface IMediaPersonCoverService
{
    /** 视图值域（顺序 = 站端按钮顺序） */
    java.util.List<String> views();

    /** 作用域：管理端默认 / 站端用户 */
    String SCOPE_DEFAULT = "default";

    String SCOPE_USER = "user";

    /**
     * 一个人物的全部视图设置（只含**有行**的视图）
     *
     * @return {@code {"poster": {kind, ref, scale, dx, dy, source}, ...}}；一条都没有 → 空 map
     */
    Map<String, Object> effectiveMap(Long personId);

    /**
     * 批量版（人物墙一页 15 人一次查完）
     *
     * @return 人物 id → {@link #effectiveMap(Long)} 的结果（没有设置的不会出现在结果里）
     */
    Map<Long, Map<String, Object>> effectiveMapByPersonIds(Collection<Long> personIds);

    /**
     * 写入一份设置（scope 决定写管理端默认还是站端用户）
     *
     * <p>「等价于自动」的组合（kind=auto 且几何 = 自动）直接删行，不留垃圾。</p>
     *
     * @return 落库后该人物的 {@link #effectiveMap(Long)}
     */
    Map<String, Object> save(Long personId, String view, String scope, String kind, String ref,
            BigDecimal scale, BigDecimal dx, BigDecimal dy);

    /**
     * 清除一份设置（回到上一层）
     *
     * @return 清完之后该人物的 {@link #effectiveMap(Long)}
     */
    Map<String, Object> clear(Long personId, String view, String scope);
}
