package com.qtools.app.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.app.exception.AppException;
import com.qtools.app.mapper.AppOwnerMapper;
import com.qtools.app.service.IAppClaimService;

/**
 * 匿名设备认领 业务实现
 *
 * <p>只改 {@code media_owner.user_id}：{@code media_favorite} / {@code media_recent} 全部按 owner_key 关联，
 * 认领后同一 owner_key 的收藏与最近浏览自然归到账号名下，不搬任何数据行。</p>
 *
 * @author qtools
 */
@Service
public class AppClaimServiceImpl implements IAppClaimService
{
    private static final Logger log = LoggerFactory.getLogger(AppClaimServiceImpl.class);

    /** 与媒体模块对 owner_key 的取值约束保持一致，避免认领接口成为注入面 */
    private static final Pattern OWNER_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Autowired
    private AppOwnerMapper appOwnerMapper;

    @Override
    public Map<String, Object> claim(Long userId, String ownerKey)
    {
        if (ownerKey == null || !OWNER_KEY.matcher(ownerKey).matches())
        {
            throw AppException.badRequest("设备标识不合法");
        }
        // 条件更新：只在「未绑定」或「已绑到自己」时生效，并发下不会互相抢
        if (appOwnerMapper.claimOwner(ownerKey, userId) <= 0)
        {
            if (appOwnerMapper.countOwner(ownerKey) <= 0)
            {
                throw new AppException(404, "设备标识不存在");
            }
            Long current = appOwnerMapper.selectOwnerUserId(ownerKey);
            if (current != null && !current.equals(userId))
            {
                throw AppException.conflict("该设备已绑定到其他账号");
            }
            // current 为空但更新影响 0 行：极窄的并发窗口，重试一次等价于幂等成功
        }
        log.info("用户端认领设备 userId={} ownerKey={}", userId, ownerKey);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("claimed", true);
        result.put("ownerKey", ownerKey);
        result.put("userId", userId);
        return result;
    }
}
