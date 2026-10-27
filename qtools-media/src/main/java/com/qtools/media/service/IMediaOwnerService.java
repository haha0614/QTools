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
    /**
     * 解析本次请求的所有者（P5.74：全站唯一的解析入口，业务代码不要自己读身份头）
     *
     * <p>判定顺序：</p>
     * <ol>
     *   <li>{@code Authorization: Bearer <app token>} 有效 → 账号态，数据归属键 {@code u:<appUserId>}；</li>
     *   <li>否则 {@code X-Ms-Owner} 请求头（其次 cookie {@code ms_owner}）→ 匿名设备态；</li>
     *   <li>都没有 → 生成新的设备键并随响应下发（Set-Cookie）。</li>
     * </ol>
     *
     * @return 同时携带数据归属键（{@link MediaOwnerSession#getOwnerKey()}）与设备键
     *         （{@link MediaOwnerSession#getDeviceKey()}）的上下文
     * @throws com.qtools.media.exception.MediaException 400：显式传了非法的 X-Ms-Owner
     *         （含客户端自称 {@code u:<id>}）
     */
    MediaOwnerSession resolve(HttpServletRequest request, HttpServletResponse response);

    /** 合并式更新 profile（nick / avatar），非法入参抛 400 */
    void applyProfile(MediaOwnerSession session, Map<String, Object> profile);
}
