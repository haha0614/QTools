package com.qtools.web.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 未登录可见性拦截器的注册（P5.78）
 *
 * <p>为什么和 {@link ApiPrefixConfig} 放在同一个模块：{@code qtools-media} 只依赖
 * {@code qtools-common}（spring-web），没有 spring-webmvc，{@code HandlerInterceptor} /
 * {@code WebMvcConfigurer} 都编不过；而拦截器的语义正好与 {@code /api} 前缀绑定
 * （见下），放在前缀配置旁边最不容易脱节。</p>
 *
 * <p>路径写 {@code /api/media/**}：{@code ApiPrefixConfig} 用 {@code addPathPrefix} 给
 * 自研模块加了 {@code /api} 前缀，拦截器的路径匹配走的是<b>请求路径</b>而不是路由模板，
 * 所以这里必须带前缀（漏了前缀 = 拦截器永远不触发，越权静默发生）。
 * {@code /api/media/admin/**} 的排除在拦截器内部做（同一个前缀下的两种身份体系：
 * 管理端走若依 token + {@code @PreAuthorize}，站端走 app token）。</p>
 *
 * @author qtools
 */
@Configuration
public class MediaGuestWebConfig implements WebMvcConfigurer
{
    @Autowired
    private MediaGuestInterceptor mediaGuestInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(mediaGuestInterceptor).addPathPatterns("/api/media/**");
    }
}
