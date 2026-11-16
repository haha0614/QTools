package com.qtools.framework.config;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.qtools.common.security.IAppTokenVerifier;
import com.qtools.framework.web.resolver.CurrentAppUserArgumentResolver;

/**
 * 自定义方法参数解析器注册
 *
 * <p>加在 {@code qtools-framework} 而不是各自的业务模块：framework 是唯一有
 * {@code spring-boot-starter-web}、且启动时必然被加载的模块，解析器放在这里，
 * 任何模块（app / media / system …）的控制器都能直接用注解。</p>
 *
 * @author qtools
 */
@Configuration
public class WebArgumentConfig implements WebMvcConfigurer
{
    private final ObjectProvider<IAppTokenVerifier> appTokenVerifier;

    public WebArgumentConfig(ObjectProvider<IAppTokenVerifier> appTokenVerifier)
    {
        this.appTokenVerifier = appTokenVerifier;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers)
    {
        resolvers.add(new CurrentAppUserArgumentResolver(appTokenVerifier));
    }
}
