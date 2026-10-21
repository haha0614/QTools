package com.qtools.app.service;

import java.util.Map;

/**
 * 匿名设备认领 业务接口
 *
 * @author qtools
 */
public interface IAppClaimService
{
    /**
     * 把 {@code media_owner.owner_key=<ownerKey>} 指向当前账号
     *
     * <p>收藏 / 最近浏览按 owner_key 关联，绑定后自然归到账号，不需要搬动数据行。</p>
     *
     * @return {@code {claimed:true,ownerKey,userId}}
     */
    Map<String, Object> claim(Long userId, String ownerKey);
}
