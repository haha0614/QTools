package com.qtools.media.service;

import java.util.Map;

/**
 * 媒体库文件监听（自动增量扫描）服务
 *
 * <p>监听每个 {@code media_root(kind='root')} 且当前存在的根目录，把文件系统事件去抖合并成一次
 * 既有的 {@code mode=scan} 任务；同一时刻仍只允许 1 个扫描任务，忙时只置 pending，等当前任务
 * 结束后补跑一次。</p>
 *
 * <p><b>常驻 Bean</b>：本接口现在总有实现（不再由 {@code qtools.media.watch.enabled} 决定是否注册）。
 * 「听不听」看 {@code sys_config.media.watchEnabled}——false 时不注册任何 WatchService、不建线程，
 * 但状态接口照常可查、{@code scanNow()} 照常可用。运行期开关受部署层总闸
 * {@code qtools.media.watch.allowRuntimeToggle} 约束。</p>
 *
 * @author qtools
 */
public interface IMediaWatchService
{
    /**
     * 状态
     *
     * <p>{@code {allowed,enabled,running,paused,debounceSeconds,quietMinutes,roots:[{id,path,registered,
     * lastEventAt,lastScanAt,pending,pendingEvents}],hint}}：{@code allowed}=部署层总闸，
     * {@code enabled}=库里的开关值，{@code debounceSeconds / quietMinutes}=当前生效值
     * （库优先、缺失 / 非法回退 yml 兜底），{@code hint}=「为什么现在不跑 / 下一步调什么」的可读文案。</p>
     */
    Map<String, Object> status();

    /**
     * 让运行期状态追上 {@code sys_config}：开 → 注册监听，关 → 停止并清理，改参数 → 下一次事件现取
     *
     * <p>幂等；启动失败只把状态留在 {@code enabled=true / running=false}（{@code hint} 说明），不抛异常。</p>
     */
    Map<String, Object> applyConfig();

    /**
     * 运行期改配置并立即生效：body {@code {enabled?, debounceSeconds?, quietMinutes?}}
     *
     * @return 最新状态（与 {@link #status()} 同结构）
     * @throws com.qtools.media.exception.MediaException 403 部署层禁止运行期开关 /
     *         400 参数非法（{@code debounceSeconds} 非 1-60、{@code quietMinutes} 非 {@code HH:mm-HH:mm}、
     *         一个字段都没给）
     */
    Map<String, Object> updateConfig(Map<String, Object> body);

    /** 部署层总闸 {@code qtools.media.watch.allowRuntimeToggle}（false = 禁止一切运行期开关） */
    boolean isRuntimeToggleAllowed();

    /** 运行期暂停（不写配置）；暂停期间事件丢弃，不触发扫描；{@code media.watchEnabled=false} 时 409 */
    Map<String, Object> pause();

    /** 运行期恢复；{@code media.watchEnabled=false} 时 409 */
    Map<String, Object> resume();

    /** 立即触发一次 {@code mode=scan}（等价管理端按钮）；已有任务在跑时由服务层返回 409 */
    Map<String, Object> scanNow();
}
