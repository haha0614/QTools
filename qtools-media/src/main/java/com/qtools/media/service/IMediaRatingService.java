package com.qtools.media.service;

import java.util.List;
import java.util.Map;

/**
 * 内容分级（P5.99）—— 单个 / 批量分级写入 + 分级「未登录可见」策略读写
 *
 * <p>为什么独立一个服务而不是塞进 {@code IMediaAdminService}：分级是<b>横切</b>于视频 / 相册、
 * 并且直接决定站端可见性的属性（写一次要同时失效「访客可见性快照」与「索引快照」两份缓存）。
 * 塞进管理端的大服务里，这两个失效点会散落在几十个同名方法之间，漏一个的表现是
 * 「管理端改完，站点 60 秒内还是旧结果 / 未登录还能看到限制级」。</p>
 *
 * @author qtools
 */
public interface IMediaRatingService
{
    /**
     * 单条视频改分级
     *
     * @param videoId 视频 id
     * @param rating  分级值（{@code general} / {@code guidance} / {@code restricted}），非法值 400
     * @return {@code {kind:"video", id, rating, label}}
     */
    Map<String, Object> updateVideoRating(String videoId, String rating);

    /**
     * 单本相册改分级（整册属性：册里的图片继承它）
     *
     * @param albumId 相册 id
     * @param rating  分级值，非法值 400
     * @return {@code {kind:"album", id, rating, label}}
     */
    Map<String, Object> updateAlbumRating(String albumId, String rating);

    /**
     * 批量改分级
     *
     * @param type   视频 / 相册（{@code video} | {@code album} | {@code videos} | {@code albums}）
     * @param ids    目标 id 清单（去空、去重；空清单 400）
     * @param rating 分级值，非法值 400
     * @return {@code {kind, rating, label, requested, matched, updated}}
     */
    Map<String, Object> batch(String type, List<String> ids, String rating);

    /**
     * 读策略：{@code {general, guidance, restricted, items:[{value,label,guestVisible}]}}
     *
     * <p>平铺三键给开关直接用；{@code items} 给「值 + 中文名 + 当前开关」一次取齐，前端不再自查表。</p>
     */
    Map<String, Object> policy();

    /**
     * 局部改策略：只改传了的项（{@code null} = 不动这一档）
     *
     * @param general    大众级是否未登录可见
     * @param guidance   家长指导级是否未登录可见
     * @param restricted 限制级是否未登录可见
     * @return 与 {@link #policy()} 同形的最新策略
     */
    Map<String, Object> updatePolicy(Boolean general, Boolean guidance, Boolean restricted);
}
