package com.qtools.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 自研业务模块的 API 前缀（{@code /api}）统一配置
 *
 * <p>为什么保留 {@code /api}：用户端是 history 路由的 SPA，页面路径就挂在根下
 * （{@code /tools}、{@code /media}、{@code /me}）。接口若也挂根下，就会和页面路径、
 * 静态目录争夺同一个命名空间（历史上 {@code /media} 同时是静态目录与接口前缀，
 * 必须同时改 nginx 的 try_files 与 {@link WebSpaConfig} 的排除表才不冲突）。
 * 有 {@code /api} 前缀后，接口与页面永不重叠，SPA 回落规则也只需「/api/** 一律不回落」。</p>
 *
 * <p>为什么放在配置里而不是每个 controller 手写：前缀是全局约定，写在 12 个
 * {@code @RequestMapping} 里既重复又容易漏。这里用 Spring 自带的
 * {@link PathMatchConfigurer#addPathPrefix} 按根包一次性加上，
 * controller 里只写业务路径（如 {@code @RequestMapping("/media")}），
 * 新增 controller 自动带前缀。</p>
 *
 * <p>作用范围仅限三个自研模块的根包；RuoYi 原生 controller
 * （{@code /system/**}、{@code /monitor/**}、{@code /common/**}、{@code /login} 等）不受影响。
 * 注意：若某个 controller 又手写 {@code "/api/..."}，最终会叠加成 {@code /api/api/...}，
 * 因此本配置与 controller 的前缀写法互斥——要么全交给这里，要么全手写。</p>
 *
 * @author qtools
 */
@Configuration
public class ApiPrefixConfig implements WebMvcConfigurer
{
    /** 统一 API 前缀 */
    private static final String API_PREFIX = "/api";

    /** 自研业务模块的根包：这些包下的 controller 自动获得 /api 前缀 */
    private static final String[] API_MODULE_PACKAGES = { "com.qtools.tool", "com.qtools.media", "com.qtools.app" };

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer)
    {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forBasePackage(API_MODULE_PACKAGES));
    }
}
