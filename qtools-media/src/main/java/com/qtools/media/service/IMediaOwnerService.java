package com.qtools.media.service;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.qtools.media.domain.MediaOwnerSession;

/**
 * 匿名所有者（owner_key）服务
 *
 * @author qtools
 */
public interface IMediaOwnerService
{
    /** 解析本次请求的 owner：X-Ms-Owner 优先，其次 cookie ms_owner，都没有则生成并下发 */
    MediaOwnerSession resolve(HttpServletRequest request, HttpServletResponse response);

    /** 合并式更新 profile（nick / avatar），非法入参抛 400 */
    void applyProfile(MediaOwnerSession session, Map<String, Object> profile);
}
