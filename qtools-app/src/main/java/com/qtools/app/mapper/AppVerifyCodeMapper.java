package com.qtools.app.mapper;

import org.apache.ibatis.annotations.Param;
import com.qtools.app.domain.AppVerifyCode;

/**
 * 用户端验证码 数据层（表 app_user_verify_code）
 *
 * <p>通道未启用时不会被调用，属 M6 预留。</p>
 *
 * @author qtools
 */
public interface AppVerifyCodeMapper
{
    /** 取最近一条未使用的验证码（同 channel + target + purpose） */
    AppVerifyCode selectLatest(@Param("channel") String channel, @Param("target") String target,
            @Param("purpose") String purpose);

    /** 记一次校验尝试（防止暴力猜码） */
    int increaseAttempts(@Param("id") Long id);

    /** 标记已使用（一次性） */
    int markUsed(@Param("id") Long id);
}
