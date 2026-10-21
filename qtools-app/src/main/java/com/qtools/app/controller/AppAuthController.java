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

/**
 * 用户端账号接口（前缀 {@code /api/app/auth}）
 *
 * <p>类上 {@link Anonymous}：让 Spring Security 放行 {@code /api/app/**}——后台链不参与用户端身份判定，
 * 是否已登录由 {@link AppJwtFilter} 裁决（白名单只有 register / login / code）。</p>
 *
 * <p>body 手工解析（而不是直接绑定 DTO），是为了非法 JSON 也回 {@code {"error":"..."}} + 400，
 * 而不是落到 framework 的 500 处理。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/api/app/auth")
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

    /** 登出：撤销当前 jti */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request)
    {
        return appAuthService.logout(currentUserId(request), AppJwtFilter.currentTokenId(request));
    }

    /** 当前用户 + 已绑定身份 */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request)
    {
        return appAuthService.me(currentUserId(request));
    }

    /** 绑定邮箱（验证码通道未启用 → 501） */
    @PostMapping("/bind/email")
    public Map<String, Object> bindEmail(@RequestBody(required = false) String rawBody, HttpServletRequest request)
    {
        return appAuthService.bind(currentUserId(request), AppUserIdentity.TYPE_EMAIL, parseObject(rawBody));
    }

    /** 绑定手机号（验证码通道未启用 → 501） */
    @PostMapping("/bind/phone")
    public Map<String, Object> bindPhone(@RequestBody(required = false) String rawBody, HttpServletRequest request)
    {
        return appAuthService.bind(currentUserId(request), AppUserIdentity.TYPE_PHONE, parseObject(rawBody));
    }

    /** 发送验证码（验证码通道未启用 → 501） */
    @PostMapping("/code")
    public Map<String, Object> sendCode(@RequestBody(required = false) String rawBody)
    {
        return appAuthService.sendCode(parseObject(rawBody));
    }

    /** 认领匿名设备：把 owner_key 的收藏 / 最近浏览归到当前账号 */
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestBody(required = false) String rawBody, HttpServletRequest request)
    {
        Map<String, Object> body = parseObject(rawBody);
        Object ownerKey = body.get("ownerKey");
        if (ownerKey == null)
        {
            throw AppException.badRequest("ownerKey 不能为空");
        }
        return appClaimService.claim(currentUserId(request), String.valueOf(ownerKey).trim());
    }

    /**
     * 当前用户 id
     *
     * <p>正常路径上 AppJwtFilter 已经拦住未登录请求，这里只是兜底（过滤器白名单外的
     * {@code /api/app/**} 一定带 token），确保不会把 null 当 userId 用。</p>
     */
    private static Long currentUserId(HttpServletRequest request)
    {
        Long userId = AppJwtFilter.currentUserId(request);
        if (userId == null)
        {
            throw AppException.unauthorized("未登录或登录已过期");
        }
        return userId;
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
