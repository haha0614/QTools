package com.qtools.media.service.impl;

import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaEditPolicyMapper;
import com.qtools.media.service.IMediaEditPolicyService;

/**
 * 媒体库「站端编辑策略」服务实现：三档 off / local / full
 *
 * <p>读取路径：sys_config['media.userEditing'] + 30s 本地缓存（{@code volatile} 双字段，
 * 单实例自托管场景不需要更重的并发结构）；写入路径：{@link #setLevel} 先 insert-if-absent
 * 再 update，写完立刻清缓存，所以「改完马上生效」。</p>
 *
 * <p>非法值（拼错、空串、NULL）一律<b>回退 full</b>并只打一条 warn：默认档位是 full，
 * 一个损坏的配置不该把自托管实例的站端编辑锁死。</p>
 *
 * @author qtools
 */
@Service
public class MediaEditPolicyServiceImpl implements IMediaEditPolicyService
{
    private static final Logger log = LoggerFactory.getLogger(MediaEditPolicyServiceImpl.class);

    /** sys_config 键名（唯一数据源） */
    public static final String CONFIG_KEY = "media.userEditing";

    public static final String LEVEL_OFF = "off";

    public static final String LEVEL_LOCAL = "local";

    public static final String LEVEL_FULL = "full";

    /** 合法档位（顺序即错误提示里的顺序） */
    public static final Set<String> LEVELS;

    static
    {
        Set<String> levels = new LinkedHashSet<>();
        levels.add(LEVEL_OFF);
        levels.add(LEVEL_LOCAL);
        levels.add(LEVEL_FULL);
        LEVELS = java.util.Collections.unmodifiableSet(levels);
    }

    /** 非 full 时站端写接口的 403 文案（前端直接展示，因此定稿在这里） */
    public static final String BLOCKED_MESSAGE = "当前为演示/本地模式，站端不允许写入（管理员可在媒体库「编辑策略」中改为完全编辑）";

    /** 本地缓存有效期：30s 内同一档位不查库 */
    private static final long CACHE_TTL_MS = 30_000L;

    @Autowired
    private MediaEditPolicyMapper mediaEditPolicyMapper;

    /** 缓存值（已归一化，一定是三个合法值之一） */
    private volatile String cachedLevel;

    /** 缓存写入时刻（System.currentTimeMillis） */
    private volatile long cachedAt;

    @Override
    public String getLevel()
    {
        long now = System.currentTimeMillis();
        String level = cachedLevel;
        if (level != null && now - cachedAt < CACHE_TTL_MS)
        {
            return level;
        }
        String resolved = resolve(mediaEditPolicyMapper.selectValueByKey(CONFIG_KEY));
        cachedLevel = resolved;
        cachedAt = now;
        return resolved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setLevel(String level)
    {
        String clean = trimToNull(level);
        if (clean == null || !LEVELS.contains(clean))
        {
            throw MediaException.badRequest("level 只能是 " + String.join(" / ", LEVELS) + "，本次: " + level);
        }
        // 没有唯一索引，只能先补行再覆盖：两步都是幂等的
        mediaEditPolicyMapper.insertIfAbsent(CONFIG_KEY, clean);
        mediaEditPolicyMapper.updateValue(CONFIG_KEY, clean);
        invalidate();
        log.info("媒体库站端编辑策略改为 {}", clean);
    }

    @Override
    public String requireWritable()
    {
        String level = getLevel();
        if (!LEVEL_FULL.equals(level))
        {
            throw new MediaException(403, BLOCKED_MESSAGE);
        }
        return level;
    }

    /** 清空本地缓存（写后调用；30s 后自然过期，因此外部改库最迟 30s 生效） */
    public void invalidate()
    {
        cachedLevel = null;
        cachedAt = 0L;
    }

    /** 库里的值 → 合法档位；非法 / 缺失回退 full */
    private static String resolve(String raw)
    {
        String clean = trimToNull(raw);
        if (clean != null)
        {
            String lower = clean.toLowerCase();
            if (LEVELS.contains(lower))
            {
                return lower;
            }
            log.warn("sys_config.{} 的值非法（{}），回退 {}", CONFIG_KEY, raw, LEVEL_FULL);
        }
        return LEVEL_FULL;
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
