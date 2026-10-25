package com.qtools.media.config;

/**
 * 自动扫描（文件监听）的<b>运行期</b>配置快照
 *
 * <p>三个值都是「sys_config 优先、非法 / 缺失回退 yml 兜底」的最终结果，{@code MediaWatchConfigService}
 * 每次解析产出这样一份不可变快照，因此一次 {@code PUT} 之后读到的三个值一定属于同一次解析，
 * 不会出现「开关是新的、去抖还是旧的」这种撕裂。</p>
 *
 * @author qtools
 */
public final class MediaWatchConfig
{
    private final boolean enabled;

    private final int debounceSeconds;

    /** 空串 = 不静默（不是 null：对外视图统一给字符串） */
    private final String quietMinutes;

    public MediaWatchConfig(boolean enabled, int debounceSeconds, String quietMinutes)
    {
        this.enabled = enabled;
        this.debounceSeconds = debounceSeconds;
        this.quietMinutes = quietMinutes == null ? "" : quietMinutes;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /** 去抖窗口（秒），已保证在 1-60 之间 */
    public int getDebounceSeconds()
    {
        return debounceSeconds;
    }

    /** 静默区间原文（{@code HH:mm-HH:mm} 或 起始-结束 分钟），空串 = 不静默 */
    public String getQuietMinutes()
    {
        return quietMinutes;
    }

    @Override
    public String toString()
    {
        return "MediaWatchConfig[enabled=" + enabled + ", debounceSeconds=" + debounceSeconds
                + ", quietMinutes=" + quietMinutes + "]";
    }
}
