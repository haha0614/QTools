package com.qtools.common.security;

/**
 * 用户端（{@code /api/app/**}）token 校验的公共门面（P5.74）
 *
 * <p>为什么要这一层：站端身份有<b>两套</b>——后台 token（framework）与用户端 app token（qtools-app）。
 * 媒体模块（qtools-media）需要在<b>自己的匿名接口</b>上识别「这次请求是不是某个 app 账号」，
 * 但 qtools-media 只依赖 qtools-common：让它去依赖 qtools-app 会把「业务模块 → 账号模块」焊死，
 * 也把 app 的 JWT 实现细节（密钥、claim、会话表）漏进媒体模块。</p>
 *
 * <p>因此本模块只声明「给我一个 token，还我一个 appUserId（无效/过期/未登录返回 null）」，
 * 由 {@code qtools-app} 提供唯一实现。媒体模块用 {@code ObjectProvider} 取用：
 * 没有实现时（单模块测试、只装媒体模块）一切回落到匿名设备态，不会因为缺 bean 启动失败。</p>
 *
 * <p>契约里<b>没有</b>「抛异常」这条路径：调用方（媒体站匿名接口）要的是「登录就用账号态、
 * 没登录就用设备态」，任何校验失败都只是「没登录」，不该变成 401（媒体站匿名必须可用）。</p>
 *
 * @author qtools
 */
public interface IAppTokenVerifier
{
    /**
     * 校验 app token
     *
     * @param token 不带 {@code Bearer } 前缀的原始 token（可为 null / 空）
     * @return 校验通过返回 app_user.id；否则返回 null（不抛异常）
     */
    Long verifyUserId(String token);
}
