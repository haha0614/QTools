package com.qtools.app.mapper;

import org.apache.ibatis.annotations.Param;
import com.qtools.app.domain.AppUserToken;

/**
 * 用户端会话 数据层（表 app_user_token）
 *
 * <p>JWT 本身无状态，撤销的唯一权威在这张表：登出把 jti 置 revoked=1 后，
 * 同一个 token 即使签名与有效期都正常也必须被拒绝。</p>
 *
 * @author qtools
 */
public interface AppUserTokenMapper
{
    /** 记录一次登录会话 */
    int insert(AppUserToken token);

    /** 该 jti 是否仍然有效（未撤销且未过期） */
    int countValidJti(@Param("jti") String jti);

    /** 撤销该 jti（幂等） */
    int revoke(@Param("jti") String jti);

    /** 撤销该账号的全部会话（重置密码 / 封禁时强制下线，P5.81） */
    int revokeAllByUser(@Param("userId") Long userId);

    /** 删除该账号的全部会话行（真删除账号前级联清理，P5.81） */
    int deleteByUser(@Param("userId") Long userId);
}
