package com.qtools.shortlink.service;

import com.qtools.shortlink.domain.ShortLink;

public interface IShortLinkService {
    /**
     * 根据用户id和长连接生成对应短链接
     * @param userId 用户id
     * @param longUrl 长连接
     * @param alias 自定义别名
     * @param expireDays 有效期天数
     * @param password 访问密码
     * @return 短链接对象
     */
    ShortLink createShortLink(Long userId, String longUrl, String alias, Integer expireDays, String password);

    /**
     * 校验短码并统计
     * @param code 短码
     */
    void redirect(String code);
}
