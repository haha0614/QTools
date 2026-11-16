package com.qtools.shortlink.controller;

import com.qtools.common.annotation.Anonymous;
import com.qtools.common.annotation.CurrentAppUser;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.exception.ServiceException;
import com.qtools.common.utils.StringUtils;
import com.qtools.shortlink.config.ShortProperties;
import com.qtools.shortlink.domain.ShortLink;
import com.qtools.shortlink.service.IShortLinkService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Anonymous
@RestController
@RequestMapping("/short")
public class ShortLinkController {
    @Autowired
    private IShortLinkService shortLinkService;
    @Autowired
    private ShortProperties shortProperties;

    @PostMapping("/create")
    public AjaxResult create(@CurrentAppUser Long userId, @RequestBody CreateShortLinkRequest request) {
        log.info("创建短链接 userId={} url={}", userId, request.getLongUrl());
        ShortLink shortLink ;
        try {
            shortLink = shortLinkService.createShortLink(userId,request.getLongUrl(),request.getAlias(),request.getExpireDays(),request.getPassword());
        }catch (ServiceException e){
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shortCode", shortLink.getShortCode());
        data.put("shortUrl", StringUtils.removeEnd(shortProperties.getDomain(), "/") + "/s/" + shortLink.getShortCode());
        data.put("longUrl", request.getLongUrl());
        data.put("status", shortLink.getStatus());
        data.put("enabled",  "0".equals(shortLink.getStatus()));
        data.put("expireAt", shortLink.getExpireAt() == null ? null
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(shortLink.getExpireAt()));
        data.put("hasPassword", !StringUtils.isEmpty(shortLink.getPassword()));

        return AjaxResult.success("操作成功", data);
    }

    /* 必须是 static：
       非静态内部类天生持有一个外部类实例引用，Jackson 反序列化时造不出它，
       就会报「Type definition error: [simple type, class ...$CreateShortLinkRequest]」。 */
    @Data
    public static class CreateShortLinkRequest {
        private String longUrl;
        private String alias;
        private Integer expireDays;
        private String password;
    }
}
