package com.qtools.media.service;

import java.util.Map;

/**
 * 媒体库文件监听（自动增量扫描）服务
 *
 * <p>监听每个 {@code media_root(kind='root')} 且当前存在的根目录，把文件系统事件去抖合并成一次
 * 既有的 {@code mode=scan} 任务；同一时刻仍只允许 1 个扫描任务，忙时只置 pending，等当前任务
 * 结束后补跑一次。</p>
 *
 * <p>实现由 {@code qtools.media.watch.enabled} 决定是否注册（默认 false）。关闭时本接口没有实现
 * 任何 bean，管理端 {@code GET /api/media/admin/watch} 仍可读，只是回 {@code enabled=false}。</p>
 *
 * @author qtools
 */
public interface IMediaWatchService
{
    /** 状态：{@code {enabled,running,paused,debounceSeconds,quietMinutes,roots:[{id,path,registered,lastEventAt,lastScanAt,pending}]}} */
    Map<String, Object> status();

    /** 运行期暂停（不写配置，重启后以配置为准）；暂停期间事件丢弃，不触发扫描 */
    Map<String, Object> pause();

    /** 运行期恢复 */
    Map<String, Object> resume();

    /** 立即触发一次 {@code mode=scan}（等价管理端按钮）；已有任务在跑时由服务层返回 409 */
    Map<String, Object> scanNow();
}
