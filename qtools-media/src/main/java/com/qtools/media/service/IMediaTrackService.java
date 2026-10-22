package com.qtools.media.service;

import com.qtools.media.domain.dto.MediaTrackDTO;

/**
 * 媒体事件埋点 业务层
 *
 * <p>本服务的方法一律<b>不抛异常</b>：埋点是尽力而为的旁路，任何失败都只记日志，
 * 绝不影响页面与业务接口的返回。</p>
 *
 * @author qtools
 */
public interface IMediaTrackService
{
    /** 接口上报（匿名）：入参不合法时静默丢弃 */
    void track(MediaTrackDTO dto);

    /** 服务端埋点（收藏/取消收藏等）：字段与 {@link MediaTrackDTO} 同义 */
    void trackEvent(String eventType, String targetType, String targetKey, String ownerKey, Long valueNum);
}
