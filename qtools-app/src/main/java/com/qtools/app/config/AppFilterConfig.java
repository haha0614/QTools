package com.qtools.app.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import com.qtools.app.security.AppJwtFilter;
import com.qtools.app.security.AppJwtService;

/**
 * 用户端过滤器注册
 *
 * <p>{@link AppJwtFilter} 只挂在 {@code /api/app/*} 上，并且排在 Spring Security 过滤链之前
 * （{@code SecurityProperties.DEFAULT_FILTER_ORDER = -100}）：这样 {@code /api/app/**} 是否已登录
 * 完全由本模块裁决，未授权请求拿到的是本模块的 401 JSON，而不是若依的 {@code HTTP 200 + {code:401}}。</p>
 *
 * <p>刻意不用 {@code @Component}：Spring Boot 会把 Filter 类型的 bean 自动注册到 {@code /*}，
 * 显式注册可以精确限定 urlPatterns 与顺序。</p>
 *
 * @author qtools
 */
@Configuration
public class AppFilterConfig
{
    /** Spring Security 过滤链的默认顺序为 -100，这里必须更小才会先执行 */
    private static final int APP_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

    @Bean
    public FilterRegistrationBean<AppJwtFilter> appJwtFilterRegistration(AppJwtService appJwtService)
    {
        FilterRegistrationBean<AppJwtFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AppJwtFilter(appJwtService));
        registration.addUrlPatterns("/api/app/*");
        registration.setName("appJwtFilter");
        registration.setOrder(APP_FILTER_ORDER);
        return registration;
    }
}
