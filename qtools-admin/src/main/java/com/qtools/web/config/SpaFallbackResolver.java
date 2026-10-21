package com.qtools.web.config;

import java.io.IOException;
import java.util.Locale;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * SPA history 路由回落：静态文件命中就直接返回；未命中且「看起来是前端路由」时才回 index.html。
 *
 * <p>三类请求绝不回落，交给上层按 404 处理，否则会把接口和缺失资源吞成 HTML：</p>
 * <ul>
 * <li>带扩展名的路径（具体静态文件缺失，就是 404）；</li>
 * <li>接口/框架前缀（{@code /api/**} 等，必须返回 JSON 404）。</li>
 * </ul>
 */
class SpaFallbackResolver extends PathResourceResolver
{
    private static final String INDEX = "index.html";

    /** 不得回落的前缀，小写、不含斜杠 */
    private final String[] excluded;

    SpaFallbackResolver(String[] excludedPrefixes)
    {
        this.excluded = excludedPrefixes;
    }

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException
    {
        String path = StringUtils.trimLeadingCharacter(resourcePath, '/');
        if (!StringUtils.hasText(path))
        {
            // 目录请求（/ 或 <adminPath>/）：直接给 SPA 入口
            return super.getResource(INDEX, location);
        }
        Resource resource = super.getResource(path, location);
        if (resource != null)
        {
            return resource;
        }
        return isSpaRoute(path) ? super.getResource(INDEX, location) : null;
    }

    private boolean isSpaRoute(String path)
    {
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        if (lastSegment.indexOf('.') >= 0)
        {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String prefix : excluded)
        {
            if (lower.equals(prefix) || lower.startsWith(prefix + "/"))
            {
                return false;
            }
        }
        return true;
    }
}
