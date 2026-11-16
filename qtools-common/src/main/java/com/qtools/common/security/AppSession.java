package com.qtools.common.security;

/**
 * 用户端当前会话：账号 id + 本次登录的 jti
 *
 * <p>jti 是签发这张 token 时生成的会话标识（落库在 {@code app_user_token.token_id}，唯一键）。
 * JWT 本身无状态、签名有效期内无法作废，撤销的唯一权威就是这条会话记录，所以「登出」
 * 必须拿到 jti 去 {@code update ... set revoked = 1 where token_id = ?}，只拿 userId 会把该账号
 * 所有设备一起踢掉。</p>
 *
 * <p>可用性：经 {@code AppJwtFilter} 的 {@code /api/app/**} 路径两个字段都可靠；走
 * {@link IAppTokenVerifier} 回落的路径只有 userId，此时 {@link #jti()} 为 {@code null}。</p>
 *
 * @param userId 当前用户端账号 id
 * @param jti    当前会话标识，可能为 null（见上）
 * @author qtools
 */
public record AppSession(Long userId, String jti)
{
}
