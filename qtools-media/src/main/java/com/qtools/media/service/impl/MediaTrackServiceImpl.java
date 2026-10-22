package com.qtools.media.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.ServletUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.common.utils.ip.IpUtils;
import com.qtools.media.domain.dto.MediaTrackDTO;
import com.qtools.media.mapper.MediaEventMapper;
import com.qtools.media.service.IMediaTrackService;

/**
 * 媒体事件埋点 业务层实现
 *
 * <p>写入两处：media_event（明细）+ media_stats_daily（当天汇总）。两条语句各自独立，
 * 汇总失败不影响明细，后者的缺失可以用管理端 rebuild 从明细补回来。</p>
 *
 * <p>兜底策略（埋点绝不能把页面搞挂）：
 * <ul>
 *   <li>字段为空或超长 —— 直接丢弃，只记 debug；</li>
 *   <li>数据库/线程上下文任何异常 —— try/catch 吞掉，记 warn；</li>
 *   <li>ip / user_agent 取不到 —— 允许 null，不阻断写入。</li>
 * </ul></p>
 *
 * @author qtools
 */
@Service
public class MediaTrackServiceImpl implements IMediaTrackService
{
    private static final Logger log = LoggerFactory.getLogger(MediaTrackServiceImpl.class);

    /** 与 media_event 字段长度保持一致 */
    private static final int EVENT_TYPE_MAX = 32;

    private static final int TARGET_TYPE_MAX = 16;

    private static final int TARGET_KEY_MAX = 64;

    private static final int OWNER_KEY_MAX = 64;

    private static final int IP_MAX = 64;

    private static final int USER_AGENT_MAX = 512;

    /** 播放结束事件（唯一让 progress 有意义的事件类型） */
    private static final String EVENT_PLAY_END = "play_end";

    /** 进度百分比上限 */
    private static final int PROGRESS_MAX = 100;

    @Autowired
    private MediaEventMapper mediaEventMapper;

    @Override
    public void track(MediaTrackDTO dto)
    {
        if (dto == null)
        {
            log.debug("媒体埋点入参为空，已忽略");
            return;
        }
        trackEvent(dto.getEventType(), dto.getTargetType(), dto.getTargetKey(), dto.getOwnerKey(), dto.getValueNum(),
                dto.getProgress());
    }

    @Override
    public void trackEvent(String eventType, String targetType, String targetKey, String ownerKey, Long valueNum,
            Integer progress)
    {
        String type = trimToNull(eventType);
        String target = trimToNull(targetType);
        if (type == null || type.codePointCount(0, type.length()) > EVENT_TYPE_MAX)
        {
            log.debug("媒体埋点 eventType 非法，已忽略: {}", eventType);
            return;
        }
        if (target == null || target.codePointCount(0, target.length()) > TARGET_TYPE_MAX)
        {
            log.debug("媒体埋点 targetType 非法，已忽略: {}", targetType);
            return;
        }
        String key = clip(trimToNull(targetKey), TARGET_KEY_MAX);
        String owner = clip(trimToNull(ownerKey), OWNER_KEY_MAX);
        long value = valueNum == null || valueNum < 0 ? 0L : valueNum;
        int percent = clampProgress(type, progress);
        try
        {
            mediaEventMapper.insertEvent(type, target, key, owner, value, percent, currentIp(), currentUserAgent());
            mediaEventMapper.upsertDaily(type, target, key, value);
        }
        catch (Exception e)
        {
            // 埋点失败只影响统计口径，不能冒泡到接口
            log.warn("媒体埋点写入失败（已忽略）event={} target={}:{} 原因={}", type, target, key, e.getMessage());
        }
    }

    /** 客户端 IP：优先代理头，取不到时 IpUtils 回 "unknown"，超长截断（x-forwarded-for 可能是一串） */
    private static String currentIp()
    {
        return clip(IpUtils.getIpAddr(), IP_MAX);
    }

    /**
     * 服务端夹取播放进度，不信任客户端：
     * 非 play_end 事件一律 0；play_end 时缺省 0，越界按 [0,100] 边界截断。
     */
    private static int clampProgress(String eventType, Integer progress)
    {
        if (!EVENT_PLAY_END.equals(eventType) || progress == null)
        {
            return 0;
        }
        if (progress < 0)
        {
            return 0;
        }
        return Math.min(progress, PROGRESS_MAX);
    }

    private static String currentUserAgent()
    {
        HttpServletRequest request = ServletUtils.getRequest();
        return request == null ? null : clip(request.getHeader("User-Agent"), USER_AGENT_MAX);
    }

    private static String trimToNull(String value)
    {
        if (StringUtils.isEmpty(value))
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 按码点截断，避免把 emoji 切坏；null 视为空串（target_key 是 NOT NULL DEFAULT ''） */
    private static String clip(String value, int max)
    {
        if (value == null)
        {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= max)
        {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
