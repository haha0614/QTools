package com.qtools.shortlink.controller;

import com.qtools.common.annotation.Anonymous;
import com.qtools.common.exception.ServiceException;
import com.qtools.shortlink.service.IShortLinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Anonymous
@RestController
public class ShortRedirectController {
    @Autowired
    private IShortLinkService shortLinkService;

    @GetMapping("/s/{code}")
    public ResponseEntity<Object> redirect(@PathVariable String code) {
        try {
            shortLinkService.redirect(code);
        // TODO 短链跳转尚未实现：ShortLinkServiceImpl#redirect 目前是空方法，
        //  这里先明确返回 501 而不是伪造成功；实现后改成 302 跳到 longUrl。
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("短链跳转尚未实现");
        } catch (ServiceException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
