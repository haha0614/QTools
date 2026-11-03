package com.qtools.app.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.qtools.app.security.AppUserGuard;
import com.qtools.common.security.AppUserProfile;
import com.qtools.common.security.IAppTokenVerifier;

import javax.sql.DataSource;

/**
 * {@link IAppTokenVerifier} 的唯一实现：直接复用用户端的 {@link AppJwtService#verify(String)}（P5.74）
 *
 * <p>不复制任何校验逻辑：签名 / iss / scope / 过期 / 会话未撤销这五道关卡都还在 AppJwtService 里，
 * 媒体模块拿到的只是「这个 token 值不值得信任」的结论。用户端 token 的信任边界因此仍然只有一处。</p>
 *
 * <p>P6.26 追加 {@link #profileOf(Long)}：媒体模块要按账号的可见上限过滤内容，但又不该依赖
 * qtools-app 的 mapper；这里只回 role + max_rating 两个字段（一次点查，主键）。</p>
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

    /** P6.26：只用来读 app_user 的 role / max_rating；查不到不影响主流程 */
    @Autowired(required = false)
    private DataSource dataSource;

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

    @Override
    public AppUserProfile profileOf(Long userId)
    {
        if (userId == null || dataSource == null)
        {
            return null;
        }
        try
        {
            return new JdbcTemplate(dataSource).queryForObject(
                    "select role, max_rating from app_user where id = ?",
                    (rs, rowNum) -> new AppUserProfile(rs.getString(1), rs.getString(2)),
                    userId);
        }
        catch (RuntimeException e)
        {
            // 老库还没跑迁移（没有 max_rating 列）时按「查不到档案」处理，不阻断媒体站
            return null;
        }
    }
}
