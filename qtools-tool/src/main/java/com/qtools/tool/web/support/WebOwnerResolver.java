package com.qtools.tool.web.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.common.security.IAppTokenVerifier;

/**
 * 「内容聚合」的归属键解析（P6.113）
 *
 * <p>与媒体站同一套约定：{@code Authorization: Bearer <app token>} 有效时是账号态 {@code u:<userId>}，
 * 否则退回设备键（前端 {@code localStorage['ms.owner']}，通过 {@code X-Ms-Owner} 传）。
 * 收藏、浏览记录、标签黑名单都靠它归属同一个人。</p>
 *
 * @author qtools
 */
@Component
public class WebOwnerResolver
{
    private static final Logger log = LoggerFactory.getLogger(WebOwnerResolver.class);

    @Autowired(required = false)
    private IAppTokenVerifier appTokenVerifier;

    /** 解析归属键；无法确定时返回 null（调用方按匿名无数据/不拦截处理） */
    public String resolve(String authorization, String ownerHeader, String ownerParam)
    {
        if (authorization != null && authorization.startsWith("Bearer ") && appTokenVerifier != null)
        {
            try
            {
                Long userId = appTokenVerifier.verifyUserId(authorization.substring(7).trim());
                if (userId != null)
                {
                    return "u:" + userId;
                }
            }
            catch (RuntimeException e)
            {
                log.debug("token 校验异常，按匿名处理: {}", e.toString());
            }
        }
        String raw = ownerHeader != null && !ownerHeader.trim().isEmpty() ? ownerHeader.trim()
                : (ownerParam == null ? "" : ownerParam.trim());
        if (raw.isEmpty() || raw.startsWith("u:"))
        {
            return null;
        }
        return raw.matches("[A-Za-z0-9._-]{4,64}") ? raw : null;
    }
}
