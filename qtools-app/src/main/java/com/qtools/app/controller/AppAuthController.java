package com.qtools.app.controller;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qtools.app.domain.AppUserIdentity;
import com.qtools.app.exception.AppException;
import com.qtools.app.security.AppJwtFilter;
import com.qtools.app.service.IAppAuthService;
import com.qtools.app.service.IAppClaimService;
import com.qtools.common.annotation.Anonymous;
import com.qtools.common.annotation.CurrentAppUser;
import com.qtools.common.security.AppSession;

/**
 * 用户端账号接口（前缀 {@code /api/app/auth}）
 *
 * <p>类上 {@link Anonymous}：让 Spring Security 放行 {@code /api/app/**}——后台链不参与用户端身份判定，
 * 是否已登录由 {@link AppJwtFilter} 裁决（白名单只有 register / login / code）。</p>
 *
 * <p>需要登录态的接口一律用 {@link CurrentAppUser} 注入当前账号：解析器读 {@link AppJwtFilter} 已经写好
 * 的 request attribute（{@code appUserId} / {@code appTokenId}），不查库、不重复验签；控制器里因此
 * 不再出现 {@code AppJwtFilter.currentUserId(request)} 之类的手写解析。</p>
 *
 * <p>body 手工解析（而不是直接绑定 DTO），是为了非法 JSON 也回 {@code {"error":"..."}} + 400，
 * 而不是落到 framework 的 500 处理。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/app/auth")
public class AppAuthController
{
    /** 只用于解析请求体，不参与响应序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IAppAuthService appAuthService;

    @Autowired
    private IAppClaimService appClaimService;

    /** 注册（invite 模式必须带有效邀请码） */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody(required = false) String rawBody, HttpServletRequest request)
    {
        return appAuthService.register(parseObject(rawBody), request);
    }

    /** 登录：identifier 先按 username，再按已绑定的 email / phone 匹配 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) String rawBody, HttpServletRequest request)
    {
        return appAuthService.login(parseObject(rawBody), request);
    }

    /** 登出：用注入了 jti 的会话撤销「当前这一个会话」，不影响该账号在其它设备上的登录 */
    @PostMapping("/logout")
    public Map<String, Object> logout(@CurrentAppUser AppSession session)
    {
        return appAuthService.logout(session.userId(), session.jti());
    }

    /** 当前用户 + 已绑定身份 */
    @GetMapping("/me")
    public Map<String, Object> me(@CurrentAppUser Long userId)
    {
        return appAuthService.me(userId);
    }

    /** 绑定邮箱（验证码通道未启用 → 501） */
    @PostMapping("/bind/email")
    public Map<String, Object> bindEmail(@RequestBody(required = false) String rawBody, @CurrentAppUser Long userId)
    {
        return appAuthService.bind(userId, AppUserIdentity.TYPE_EMAIL, parseObject(rawBody));
    }

    /** 绑定手机号（验证码通道未启用 → 501） */
    @PostMapping("/bind/phone")
    public Map<String, Object> bindPhone(@RequestBody(required = false) String rawBody, @CurrentAppUser Long userId)
    {
        return appAuthService.bind(userId, AppUserIdentity.TYPE_PHONE, parseObject(rawBody));
    }

    /** 发送验证码（验证码通道未启用 → 501） */
    @PostMapping("/code")
    public Map<String, Object> sendCode(@RequestBody(required = false) String rawBody)
    {
        return appAuthService.sendCode(parseObject(rawBody));
    }

    /** 认领匿名设备：把 owner_key 的收藏 / 最近浏览归到当前账号 */
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody(required = false) String rawBody, @CurrentAppUser Long userId)
    {
        Map<String, Object> body = parseObject(rawBody);
        Object ownerKey = body.get("ownerKey");
        if (ownerKey == null)
        {
            throw AppException.badRequest("ownerKey 不能为空");
        }
        return appClaimService.claim(userId, String.valueOf(ownerKey).trim());
    }

    /** 解析 body；缺 body 视为空对象（缺字段由业务层给 400） */
    private static Map<String, Object> parseObject(String rawBody)
    {
        if (rawBody == null || rawBody.trim().isEmpty())
        {
            return Map.of();
        }
        Object parsed;
        try
        {
            parsed = MAPPER.readValue(rawBody, Object.class);
        }
        catch (Exception e)
        {
            throw AppException.badRequest("body 不是合法 JSON");
        }
        if (!(parsed instanceof Map))
        {
            throw AppException.badRequest("body 必须是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }
}
