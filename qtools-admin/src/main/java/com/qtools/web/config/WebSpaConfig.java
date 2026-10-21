package com.qtools.web.config;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 单端口静态托管（M4-A）：8080 同时提供用户端 SPA、管理端 SPA 与已有 API。
 *
 * <p>四段映射彼此独立，互不回落：</p>
 * <ul>
 * <li>{@code /media/**} → {@code userDist/media}：媒体前端页面（M4-B 产物）。找不到就 404，
 * <b>不做</b> SPA 回落，且与接口前缀 {@code /api/media/**} 分属不同路径，不会互相吞;</li>
 * <li>{@code /assets/**}、{@code /static/**}、{@code <adminPath>/static/**}：带 content hash 的
 * 构建产物，给一年强缓存;</li>
 * <li>{@code /**} 与 {@code <adminPath>/**}：SPA 静态文件，缺失时回落各自 index.html
 * （history 路由），入口给 no-cache。</li>
 * </ul>
 *
 * <p>构建产物目录缺失只记 WARN、不阻断启动：没构建前端也要能起后端，对应路径按 404 返回说明。</p>
 */
@Configuration
public class WebSpaConfig implements WebMvcConfigurer
{
    private static final Logger log = LoggerFactory.getLogger(WebSpaConfig.class);

    /** 带 content hash 的产物：一年强缓存，命中即不回头 */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();

    /** SPA 入口与页面：每次回源校验，保证发版即时生效 */
    private static final CacheControl NO_CACHE = CacheControl.noCache();

    /** 这些前缀属于接口或框架端点，绝不回落到 index.html */
    static final String[] API_PREFIXES = { "api", "system", "monitor", "common", "quartz", "tool/gen", "test", "profile", "druid", "v3", "swagger-ui", "media", "actuator" };

    /** 用户端 SPA 构建产物目录 */
    @Value("${qtools.web.userDist:}")
    private String userDist;

    /** 管理端 SPA 构建产物目录 */
    @Value("${qtools.web.adminDist:}")
    private String adminDist;

    /** 管理端 SPA 挂载路径 */
    @Value("${qtools.web.adminPath:/admin-ui}")
    private String adminPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry)
    {
        Path userRoot = distRoot("用户端 SPA", "qtools.web.userDist", userDist);
        Path adminRoot = distRoot("管理端 SPA", "qtools.web.adminDist", adminDist);
        String admin = adminPathName();

        if (userRoot != null)
        {
            // 媒体前端页面：显式映射、不回落，找不到即 404
            registry.addResourceHandler("/media/**")
                    .addResourceLocations(userRoot.resolve("media").toUri().toString())
                    .setCacheControl(NO_CACHE);

            registry.addResourceHandler("/assets/**")
                    .addResourceLocations(userRoot.resolve("assets").toUri().toString())
                    .setCacheControl(IMMUTABLE);

            registry.addResourceHandler("/static/**")
                    .addResourceLocations(userRoot.resolve("static").toUri().toString())
                    .setCacheControl(IMMUTABLE);

            // 用户端 SPA：先取静态文件，未命中且像前端路由时才回 index.html
            registry.addResourceHandler("/**")
                    .addResourceLocations(userRoot.toUri().toString())
                    .setCacheControl(NO_CACHE)
                    .resourceChain(false)
                    .addResolver(new SpaFallbackResolver(userExclusions(admin)));
        }

        if (adminRoot != null && !admin.isBlank())
        {
            registry.addResourceHandler("/" + admin + "/static/**")
                    .addResourceLocations(adminRoot.resolve("static").toUri().toString())
                    .setCacheControl(IMMUTABLE);

            registry.addResourceHandler("/" + admin + "/**")
                    .addResourceLocations(adminRoot.toUri().toString())
                    .setCacheControl(NO_CACHE)
                    .resourceChain(false)
                    // 管理端前缀下没有接口，路由名与接口同名（system/user 等），因此不排除任何前缀
                    .addResolver(new SpaFallbackResolver(new String[0]));
        }
    }

    /**
     * 用户端 SPA 的排除前缀：接口/框架端点 + 管理端挂载路径。
     */
    private static String[] userExclusions(String adminPath)
    {
        if (!StringUtils.hasText(adminPath))
        {
            return API_PREFIXES;
        }
        String[] merged = new String[API_PREFIXES.length + 1];
        System.arraycopy(API_PREFIXES, 0, merged, 0, API_PREFIXES.length);
        merged[API_PREFIXES.length] = adminPath;
        return merged;
    }

    /**
     * 校验构建产物目录：缺失只 WARN，返回 null 表示该段不注册（对应路径最终 404，启动照常成功）。
     */
    private Path distRoot(String label, String key, String value)
    {
        if (!StringUtils.hasText(value))
        {
            log.warn("未配置 {}（{}），该段路径不托管，访问将返回 404；先执行 bin\\build-web.bat 构建前端", label, key);
            return null;
        }
        try
        {
            Path root = Paths.get(value.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root))
            {
                log.warn("{} 目录不存在：{}（{}），访问将返回 404，后端继续启动；先执行 bin\\build-web.bat 构建前端", label, root, key);
                return null;
            }
            log.info("{} 托管目录：{}", label, root);
            return root;
        }
        catch (InvalidPathException e)
        {
            log.warn("{} 的配置不是合法路径：{}（{}），访问将返回 404", label, value, key);
            return null;
        }
    }

    /**
     * 管理端挂载路径归一化为不含首尾斜杠的单段路径；占用接口前缀时直接放弃注册，避免吞掉接口。
     */
    private String adminPathName()
    {
        String path = StringUtils.trimWhitespace(adminPath);
        if (!StringUtils.hasText(path) || "/".equals(path))
        {
            log.warn("qtools.web.adminPath 为空或为 /，不托管管理端 SPA");
            return "";
        }
        path = StringUtils.trimTrailingCharacter(StringUtils.trimLeadingCharacter(path, '/'), '/');
        String first = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        for (String prefix : API_PREFIXES)
        {
            if (prefix.equalsIgnoreCase(first))
            {
                log.warn("qtools.web.adminPath={} 与接口前缀 /{} 冲突，不托管管理端 SPA", adminPath, prefix);
                return "";
            }
        }
        return path;
    }
}
