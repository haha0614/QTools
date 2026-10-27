package com.qtools.app.service;

import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户端账号 业务接口（注册 / 登录 / 登出 / 当前用户 / 绑定 / 验证码）
 *
 * @author qtools
 */
public interface IAppAuthService
{
    /**
     * 注册：返回 {@code {user:{id,username,nickname,avatar}, pending:true, message:"注册成功，等待站长审核"}}
     *
     * <p>P5.79 起注册<b>不再返回 token</b>：账号写入 {@code status=pending}，必须由管理端审核通过后才能登录。</p>
     */
    Map<String, Object> register(Map<String, Object> body, HttpServletRequest request);

    /** 登录：返回 {@code {token,expiresIn,user}}；待审核 / 未通过审核回 403 + 明确文案 */
    Map<String, Object> login(Map<String, Object> body, HttpServletRequest request);

    /** 登出：撤销当前 jti，返回 {@code {ok:true}} */
    Map<String, Object> logout(Long userId, String jti);

    /** 当前用户：含已绑定身份列表、注册时间、最近登录时间 */
    Map<String, Object> me(Long userId);

    /** 绑定邮箱 / 手机号（验证码通道关闭时 501） */
    Map<String, Object> bind(Long userId, String type, Map<String, Object> body);

    /** 发送验证码（通道关闭时 501） */
    Map<String, Object> sendCode(Map<String, Object> body);
}
