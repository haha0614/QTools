package com.qtools.web.controller.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import com.qtools.common.annotation.Anonymous;

/**
 * SPA 入口：把目录请求转到各自的 index.html。
 *
 * <p>Spring 的 ResourceHandler 对空资源路径直接返回 null（Spring Boot 平时由 WelcomePageHandlerMapping
 * 兜底，而它随 {@code spring.web.resources.add-mappings=false} 一起关掉），所以 {@code /} 与
 * {@code <adminPath>/} 必须显式 forward，否则首页 404。</p>
 *
 * <p>只映射三个精确路径，不带通配，{@code @Anonymous} 因此只放行这三条，不会放大成别的路径的白名单。</p>
 */
@Anonymous
@Controller
public class SpaEntryController
{
    @Value("${qtools.web.adminPath:/admin-ui}")
    private String adminPath;

    /** 用户端 SPA 首页 */
    @GetMapping("/")
    public String userIndex()
    {
        return "forward:/index.html";
    }

    /** 媒体站目录：手输 /media 或 /media/ 时补到视频站首页，避免 404 */
    @GetMapping({ "/media", "/media/" })
    public String mediaEntry()
    {
        return "redirect:/media/index.html";
    }

    /**
     * 用户端登录/注册页。
     *
     * <p>若依后台的 {@code POST /login}、{@code POST /register} 占用了这两个路径，GET 请求会命中
     * 同路径的 POST 映射并抛 {@code HttpRequestMethodNotSupportedException}（被全局异常处理成
     * HTTP 200 + code 500），SPA 回落就永远轮不到。这里显式接住 GET，转发到用户端 SPA。</p>
     */
    @GetMapping({ "/login", "/register" })
    public String authPages()
    {
        return "forward:/index.html";
    }

    /** 管理端 SPA 入口，少写一层斜杠时不该落到 404 */
    @GetMapping("${qtools.web.adminPath:/admin-ui}")
    public String adminEntry()
    {
        String path = adminPathName();
        return StringUtils.hasText(path) ? "redirect:/" + path + "/" : "redirect:/";
    }

    /** 管理端 SPA 首页 */
    @GetMapping("${qtools.web.adminPath:/admin-ui}/")
    public String adminIndex()
    {
        String path = adminPathName();
        return StringUtils.hasText(path) ? "forward:/" + path + "/index.html" : "forward:/index.html";
    }

    private String adminPathName()
    {
        String path = StringUtils.trimWhitespace(adminPath);
        return StringUtils.hasText(path)
                ? StringUtils.trimTrailingCharacter(StringUtils.trimLeadingCharacter(path, '/'), '/')
                : "";
    }
}
