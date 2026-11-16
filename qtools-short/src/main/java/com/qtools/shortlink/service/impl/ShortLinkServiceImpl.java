package com.qtools.shortlink.service.impl;

import cn.hutool.core.date.DateUtil;
import com.qtools.common.constant.HttpStatus;
import com.qtools.common.exception.ServiceException;
import com.qtools.common.utils.StringUtils;
import com.qtools.shortlink.domain.ShortLink;
import com.qtools.shortlink.mapper.ShortLinkMapper;
import com.qtools.shortlink.service.IShortCodeService;
import com.qtools.shortlink.service.IShortLinkService;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class ShortLinkServiceImpl implements IShortLinkService {
    @Autowired
    private ShortLinkMapper shortLinkMapper;
    @Autowired
    private IShortCodeService shortCodeService;

    /**
     * 根据用户id和长链接生成短链
     * @param userId 用户id
     * @param longUrl 长连接
     * @param alias 自定义别名
     * @param expireDays 有效期天数
     * @param password 访问密码
     * @return 短链接对象
     */
    @Override
    public ShortLink createShortLink(Long userId, String longUrl, String alias, Integer expireDays, String password) {
        // 参数校验
        if (longUrl == null || StringUtils.isEmpty(longUrl)) {
            throw new ServiceException("长链不能为空", HttpStatus.BAD_REQUEST);
        }

        if (!longUrl.startsWith("http://") && !longUrl.startsWith("https://")) {
            throw new ServiceException("必须由 http/https 开头", HttpStatus.BAD_REQUEST);
        }
        if (longUrl.length() > 2048) {
            throw new ServiceException("链接过长", HttpStatus.BAD_REQUEST);
        }

        // 计算urlHash
        String urlHash = DigestUtils.sha256Hex(longUrl);
        // 判断是否设置了过期时间
        boolean hasExpireSetting = expireDays != null && expireDays > 0;
        // 判断是否设置了访问密码
        boolean hasPassword = password != null && !password.trim().isEmpty();
        // 自定义别名
        if (!StringUtils.isEmpty(alias)) {
            if (!Pattern.matches("^[0-9a-zA-Z]{3,16}$", alias)) {
                throw new ServiceException("别名只能由 3-16 位字母数字组成", HttpStatus.BAD_REQUEST);
            }

            ShortLink existed = shortLinkMapper.getShortLinkByUserIdAndUrlHash(userId, urlHash);
            if (existed != null) {
                if (!"0".equals(existed.getStatus())) {
                    throw new ServiceException("该链接已存在但被停用，请先启用", HttpStatus.BAD_REQUEST);
                }
                return existed;
            }

            ShortLink sl = shortLinkMapper.getShortLinkByShortCode(alias);
            if (!Objects.isNull(sl)) {
                throw new ServiceException("别名已被占用", HttpStatus.BAD_REQUEST);
            }
            sl = new ShortLink();
            sl.setUserId(userId);
            sl.setLongUrl(longUrl);
            sl.setShortCode(alias);
            sl.setUrlHash(urlHash);
            sl.setStatus("0");

            if (hasExpireSetting) {
                sl.setExpireAt(DateUtil.offsetDay(DateUtil.date(), expireDays));
            }
            if (hasPassword) {
                sl.setPassword(DigestUtils.sha256Hex(password.trim()));
            }
            try {
                shortLinkMapper.insertShortLink(sl);
            } catch (DuplicateKeyException e) {
                // 唯一键冲突
                sl = new ShortLink();
                sl = shortLinkMapper.getShortLinkByShortCode(alias);
                if (!Objects.isNull(sl)) {
                    throw new ServiceException("别名已被占用", HttpStatus.BAD_REQUEST);
                }
                throw new ServiceException("服务器未知异常！", HttpStatus.ERROR);
            }
            return sl;
        }
        ShortLink sl = new ShortLink();
        sl.setUserId(userId);
        sl.setUrlHash(urlHash);
        ShortLink shortLink = shortLinkMapper.getShortLinkByUserIdAndUrlHash(userId, urlHash);
        if (shortLink != null ) {
            if (!"0".equals(shortLink.getStatus())) {
                throw new ServiceException("该链接已存在但被停用，请先启用", HttpStatus.BAD_REQUEST);
            }
            return shortLink;
        }
        sl.setShortCode(shortCodeService.nextCode());
        sl.setLongUrl(longUrl);
        sl.setStatus("0");
        if (hasExpireSetting) {
            sl.setExpireAt(DateUtil.offsetDay(DateUtil.date(), expireDays));
        }
        if (hasPassword) {
            sl.setPassword(DigestUtils.sha256Hex(password.trim()));
        }
        try {
            shortLinkMapper.insertShortLink(sl);
        } catch (DuplicateKeyException e) {
            // 唯一键冲突
            shortLink = new ShortLink();
            shortLink = shortLinkMapper.getShortLinkByUserIdAndUrlHash(userId, urlHash);
            if (shortLink == null) {
                throw new ServiceException("未知异常", HttpStatus.ERROR);
            }
            if (!Objects.equals(shortLink.getStatus(), "0")) {
                throw new ServiceException("链接已被禁用！", HttpStatus.BAD_REQUEST);
            }
            return shortLink;
        }
        return sl;
    }

    /**
     * 校验短码并统计
     * @param code 短码
     */
    @Override
    public void redirect(String code) {

    }
}
