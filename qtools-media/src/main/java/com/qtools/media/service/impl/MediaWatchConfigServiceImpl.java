package com.qtools.media.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.media.config.MediaProperties;
import com.qtools.media.config.MediaWatchConfig;
import com.qtools.media.mapper.MediaWatchConfigMapper;
import com.qtools.media.service.IMediaWatchConfigService;
import com.qtools.media.support.MediaQuietWindow;

/**
 * 自动扫描运行期配置实现：{@code sys_config} 三键 + 30s 本地缓存
 *
 * <p>读取路径与 {@link MediaEditPolicyServiceImpl} 完全同一套：{@code volatile} 双字段
 * （快照 + 时间戳）做 30s 缓存，写后 {@link #invalidate()}；单实例自托管不需要更重的并发结构。</p>
 *
 * <p>非法值（拼错、越界、脏数据）一律<b>回退 yml 兜底</b>并只打一条 warn：一个坏掉的库值不该让
 * 自动扫描直接失效或变成「每条事件扫一次」。{@code media.watchEnabled} 没有 yml 兜底
 * （旧键 {@code qtools.media.watch.enabled} 已迁移），缺失 / 非法一律按 <b>false</b>。</p>
 *
 * @author qtools
 */
@Service
public class MediaWatchConfigServiceImpl implements IMediaWatchConfigService
{
    private static final Logger log = LoggerFactory.getLogger(MediaWatchConfigServiceImpl.class);

    /** 是否开启自动扫描（唯一数据源；旧的 qtools.media.watch.enabled 已迁移到这一行） */
    public static final String KEY_ENABLED = "media.watchEnabled";

    /** 去抖窗口（秒），1-60 */
    public static final String KEY_DEBOUNCE_SECONDS = "media.watchDebounceSeconds";

    /** 夜间静默区间；空串 = 不静默 */
    public static final String KEY_QUIET_MINUTES = "media.watchQuietMinutes";

    /** 去抖范围（与 PUT 的校验口径一致） */
    public static final int MIN_DEBOUNCE_SECONDS = 1;

    public static final int MAX_DEBOUNCE_SECONDS = 60;

    /** 本地缓存有效期：30s 内同一份快照不查库 */
    private static final long CACHE_TTL_MS = 30_000L;

    private final MediaWatchConfigMapper mediaWatchConfigMapper;

    private final MediaProperties mediaProperties;

    /** 缓存快照（三值自洽） */
    private volatile MediaWatchConfig cached;

    /** 缓存写入时刻（System.currentTimeMillis） */
    private volatile long cachedAt;

    public MediaWatchConfigServiceImpl(MediaWatchConfigMapper mediaWatchConfigMapper,
            MediaProperties mediaProperties)
    {
        this.mediaWatchConfigMapper = mediaWatchConfigMapper;
        this.mediaProperties = mediaProperties;
    }

    // ------------------------------------------------------------------ 读

    @Override
    public MediaWatchConfig current()
    {
        long now = System.currentTimeMillis();
        MediaWatchConfig snapshot = cached;
        if (snapshot != null && now - cachedAt < CACHE_TTL_MS)
        {
            return snapshot;
        }
        MediaWatchConfig resolved = resolve();
        cached = resolved;
        cachedAt = now;
        return resolved;
    }

    @Override
    public boolean isEnabled()
    {
        return current().isEnabled();
    }

    @Override
    public int getDebounceSeconds()
    {
        return current().getDebounceSeconds();
    }

    @Override
    public String getQuietMinutes()
    {
        return current().getQuietMinutes();
    }

    /** 三键一次读齐 → 一份自洽快照；每个值各自「库里优先，缺失 / 非法回退 yml」 */
    private MediaWatchConfig resolve()
    {
        MediaProperties.Watch fallback = mediaProperties.getWatch();
        boolean enabled = resolveEnabled(read(KEY_ENABLED));
        int debounce = resolveDebounce(read(KEY_DEBOUNCE_SECONDS), fallback);
        String quiet = resolveQuiet(read(KEY_QUIET_MINUTES), fallback);
        return new MediaWatchConfig(enabled, debounce, quiet);
    }

    /** 查库失败不该让监听直接挂掉：按「没配」处理（走兜底 + warn） */
    private String read(String key)
    {
        try
        {
            return mediaWatchConfigMapper.selectValueByKey(key);
        }
        catch (Exception e)
        {
            log.warn("读取 sys_config.{} 失败，按缺失处理（用 yml 兜底）", key, e);
            return null;
        }
    }

    /**
     * {@code media.watchEnabled} → boolean；缺失 / 非法按 false
     *
     * <p>没有 yml 兜底：旧键 {@code qtools.media.watch.enabled} 已迁移，把「忘记建键」当成「关」
     * 比当成「开」安全（否则克隆一份仓库就先跑一个全盘递归监听）。</p>
     */
    private static boolean resolveEnabled(String raw)
    {
        String clean = trimToNull(raw);
        if (clean == null)
        {
            return false;
        }
        String lower = clean.toLowerCase();
        if ("true".equals(lower) || "1".equals(lower) || "on".equals(lower) || "yes".equals(lower))
        {
            return true;
        }
        if ("false".equals(lower) || "0".equals(lower) || "off".equals(lower) || "no".equals(lower))
        {
            return false;
        }
        log.warn("sys_config.{} 的值非法（{}），按 false 处理", KEY_ENABLED, raw);
        return false;
    }

    /** {@code media.watchDebounceSeconds} → 1-60；缺失 / 非法回退 yml {@code debounceSeconds}（再钳进 1-60） */
    private static int resolveDebounce(String raw, MediaProperties.Watch fallback)
    {
        String clean = trimToNull(raw);
        if (clean != null)
        {
            try
            {
                int value = Integer.parseInt(clean);
                if (value >= MIN_DEBOUNCE_SECONDS && value <= MAX_DEBOUNCE_SECONDS)
                {
                    return value;
                }
                log.warn("sys_config.{} 的值越界（{}，允许 {}-{}），回退 {}s", KEY_DEBOUNCE_SECONDS, raw,
                        MIN_DEBOUNCE_SECONDS, MAX_DEBOUNCE_SECONDS, clampDebounce(fallback));
            }
            catch (NumberFormatException e)
            {
                log.warn("sys_config.{} 的值非法（{}），回退 {}s", KEY_DEBOUNCE_SECONDS, raw, clampDebounce(fallback));
            }
        }
        return clampDebounce(fallback);
    }

    private static int clampDebounce(MediaProperties.Watch fallback)
    {
        int value = fallback == null ? 5 : fallback.getDebounceSeconds();
        if (value < MIN_DEBOUNCE_SECONDS)
        {
            return MIN_DEBOUNCE_SECONDS;
        }
        return value > MAX_DEBOUNCE_SECONDS ? MAX_DEBOUNCE_SECONDS : value;
    }

    /**
     * {@code media.watchQuietMinutes} → 原文；缺失回退 yml {@code quietMinutes}，非法回退 yml 并 warn
     *
     * <p>空串是<b>合法值</b>（= 显式表示不静默），不能当成缺失去回退。</p>
     */
    private static String resolveQuiet(String raw, MediaProperties.Watch fallback)
    {
        if (raw != null)
        {
            String clean = raw.trim();
            if (clean.isEmpty())
            {
                return "";
            }
            if (MediaQuietWindow.parse(clean) != null)
            {
                return clean;
            }
            log.warn("sys_config.{} 的值非法（{}，应为 HH:mm-HH:mm 或 起始-结束 分钟），回退 yml 兜底", KEY_QUIET_MINUTES, raw);
        }
        String fallbackValue = fallback == null || fallback.getQuietMinutes() == null ? "" : fallback.getQuietMinutes().trim();
        if (fallbackValue.isEmpty())
        {
            return "";
        }
        if (MediaQuietWindow.parse(fallbackValue) == null)
        {
            log.warn("qtools.media.watch.quietMinutes 的值也非法（{}），按不静默处理", fallbackValue);
            return "";
        }
        return fallbackValue;
    }

    // ------------------------------------------------------------------ 写

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Boolean enabled, Integer debounceSeconds, String quietMinutes)
    {
        if (enabled != null)
        {
            upsert(KEY_ENABLED, "媒体库自动扫描开关", enabled.booleanValue() ? "true" : "false",
                    "自动扫描（文件监听）：true=监听媒体根并自动触发 mode=scan / false=不注册任何 WatchService（默认）。"
                            + "运行期可改：PUT /api/media/admin/watch/config");
        }
        if (debounceSeconds != null)
        {
            upsert(KEY_DEBOUNCE_SECONDS, "媒体库自动扫描去抖秒数", String.valueOf(debounceSeconds.intValue()),
                    "自动扫描去抖窗口（秒，1-60）：窗口内 N 个文件事件合并成 1 次扫描");
        }
        if (quietMinutes != null)
        {
            String value = quietMinutes.trim();
            upsert(KEY_QUIET_MINUTES, "媒体库自动扫描夜间静默区间", value,
                    "夜间静默区间：HH:mm-HH:mm（如 23:00-07:00）或起始-结束分钟数；空=不静默");
        }
        invalidate();
        log.info("自动扫描配置已落库：enabled={} debounceSeconds={} quietMinutes={}", enabled, debounceSeconds, quietMinutes);
    }

    /** 没有唯一索引，只能先补行再覆盖：两步都幂等 */
    private void upsert(String key, String name, String value, String remark)
    {
        mediaWatchConfigMapper.insertIfAbsent(key, name, value, remark);
        mediaWatchConfigMapper.updateValue(key, value);
    }

    @Override
    public void invalidate()
    {
        cached = null;
        cachedAt = 0L;
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
