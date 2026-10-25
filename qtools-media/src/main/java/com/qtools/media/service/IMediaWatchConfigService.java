package com.qtools.media.service;

import com.qtools.media.config.MediaWatchConfig;

/**
 * 自动扫描（文件监听）的运行期配置：{@code sys_config} 三键 + 30s 本地缓存
 *
 * <p>唯一数据源是 {@code sys_config}：{@code media.watchEnabled} / {@code media.watchDebounceSeconds} /
 * {@code media.watchQuietMinutes}（建键脚本 {@code doc/media_watch_config.sql}，幂等）。yml 里的
 * {@code qtools.media.watch.debounceSeconds} / {@code quietMinutes} <b>只在</b>键缺失或值非法时兜底，
 * 不再参与「当前生效值」的判断。</p>
 *
 * <p>读取带 30s 缓存（与站端编辑策略同一套机制）：外部直接改库最迟 30s 生效；走
 * {@code PUT /api/media/admin/watch/config} 则写后立即失效，等效即时生效。</p>
 *
 * @author qtools
 */
public interface IMediaWatchConfigService
{
    /** 一次解析的完整快照（同一次解析：开关 / 去抖 / 静默一定自洽） */
    MediaWatchConfig current();

    /** 当前是否开启自动扫描（{@code media.watchEnabled}，缺失 / 非法按 false） */
    boolean isEnabled();

    /** 当前去抖秒数（1-60；键缺失 / 非法回退 yml {@code debounceSeconds}） */
    int getDebounceSeconds();

    /** 当前静默区间原文（空串 = 不静默；键缺失 / 非法回退 yml {@code quietMinutes}） */
    String getQuietMinutes();

    /**
     * 落库（null = 该字段不改，其余 insert-if-absent + update），随后立即失效缓存
     *
     * <p>取值范围校验在调用方（{@code MediaWatchService#updateConfig}）做，这里只负责写。</p>
     */
    void save(Boolean enabled, Integer debounceSeconds, String quietMinutes);

    /** 清空本地缓存（写后调用；30s 后自然过期） */
    void invalidate();
}
