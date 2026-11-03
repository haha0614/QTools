package com.qtools.web.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.common.utils.StringUtils;
import com.qtools.framework.web.service.TokenService;
import com.qtools.media.service.IAdminSessionProbe;

/**
 * 管理端登录态探针的实现（P6.08）
 *
 * <p>为什么放在 qtools-admin：只有这一层有 {@code TokenService}（若依后台 token 的解析与 Redis 会话），
 * 媒体模块不该反过来依赖它。媒体服务在判「这次是不是访客」时会先问本探针，命中就整条放行。</p>
 *
 * <p>只看 {@code Admin-Token} cookie：管理端的 {@code <img>}/XHR 都会带同源 cookie，
 * 而站端的 app token 走的是 {@code Authorization: Bearer}，两者不冲突。</p>
 *
 * @author qtools
 */
@Component
public class AdminSessionProbe implements IAdminSessionProbe
{
    /** 管理端 SPA 存后台 token 的 cookie 名（QTools-M/src/utils/auth.js 的 TokenKey） */
    private static final String ADMIN_TOKEN_COOKIE = "Admin-Token";

    @Autowired
    private TokenService tokenService;

    @Override
    public boolean isAdmin(HttpServletRequest request)
    {
        if (request == null)
        {
            return false;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0)
        {
            return false;
        }
        for (Cookie cookie : cookies)
        {
            if (ADMIN_TOKEN_COOKIE.equals(cookie.getName()) && StringUtils.isNotEmpty(cookie.getValue()))
            {
                return tokenService.getLoginUserByToken(cookie.getValue()) != null;
            }
        }
        return false;
    }
}
