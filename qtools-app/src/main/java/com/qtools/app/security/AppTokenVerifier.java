package com.qtools.app.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.app.security.AppUserGuard;
import com.qtools.common.security.IAppTokenVerifier;

/**
 * {@link IAppTokenVerifier} 的唯一实现：直接复用用户端的 {@link AppJwtService#verify(String)}（P5.74）
 *
 * <p>不复制任何校验逻辑：签名 / iss / scope / 过期 / 会话未撤销这五道关卡都还在 AppJwtService 里，
 * 媒体模块拿到的只是「这个 token 值不值得信任」的结论。用户端 token 的信任边界因此仍然只有一处。</p>
 *
 * @author qtools
 */
@Component
public class AppTokenVerifier implements IAppTokenVerifier
{
    @Autowired
    private AppJwtService appJwtService;

    /** P5.79：审核状态未通过的账号，其 token 同样不被承认（媒体侧与用户端同一口径） */
    @Autowired
    private AppUserGuard appUserGuard;

    @Override
    public Long verifyUserId(String token)
    {
        if (token == null || token.isBlank())
        {
            return null;
        }
        AppJwtService.Principal principal = appJwtService.verify(token);
        if (principal == null)
        {
            return null;
        }
        return appUserGuard.blockReasonOf(principal.getUserId()) == null ? principal.getUserId() : null;
    }
}
