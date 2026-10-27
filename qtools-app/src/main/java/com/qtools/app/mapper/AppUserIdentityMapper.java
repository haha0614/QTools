package com.qtools.app.mapper;

import java.util.List;
import com.qtools.app.domain.AppUserIdentity;

/**
 * 用户端登录身份 数据层（表 app_user_identity）
 *
 * @author qtools
 */
public interface AppUserIdentityMapper
{
    /** 某账号已绑定的全部身份 */
    List<AppUserIdentity> selectByUserId(Long userId);

    /** 绑定身份；(type, identifier) 有唯一键，重复绑会抛 DuplicateKeyException */
    int insert(AppUserIdentity identity);

    /** 删除该账号的全部身份（真删除账号前级联清理，P5.81） */
    int deleteByUserId(Long userId);
}
