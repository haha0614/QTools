package com.qtools.framework.web.resolver;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import com.qtools.common.annotation.CurrentAppUser;
import com.qtools.common.exception.AppUnauthorizedException;
import com.qtools.common.security.AppRequestAttributes;
import com.qtools.common.security.AppSession;
import com.qtools.common.security.AppUserProfile;
import com.qtools.common.security.IAppTokenVerifier;

/**
 * {@link CurrentAppUser} 的参数解析器：把当前用户端账号填进控制器方法参数
 *
 * <p>取值分两条路，先便宜后昂贵：</p>
 *
 * <ol>
 *   <li><b>读 request attribute</b>（{@code /api/app/**}）：{@code AppJwtFilter} 已经验过签、查过会话表，
 *       把 id / jti 写在 {@link AppRequestAttributes} 上，这里只取——零查询、零验签；</li>
 *   <li><b>回落自验</b>（其它路径，例如 {@code /api/media/**}）：自己拆 {@code Authorization: Bearer}，
 *       交给 {@link IAppTokenVerifier} 验（common 的 SPI，qtools-app 是唯一实现）。这条路径不复制任何
 *       密钥 / claim 逻辑，验签仍然只有 {@code AppJwtService} 一处；且拿不到 jti。</li>
 * </ol>
 *
 * <p>类型不支持时<b>直接抛</b>而不是注 null：注解写错属于开发期错误，应该立刻炸出来，
 * 而不是让业务拿到一个「看起来没登录」的 null 去猜。</p>
 *
 * @author qtools
 */
public class CurrentAppUserArgumentResolver implements HandlerMethodArgumentResolver
{
    /** 与 {@code AppJwtFilter} / {@code BearerTokens} 同一口径：大小写不敏感、前后空白裁掉 */
    private static final String BEARER = "Bearer ";

    /** 与 {@code AppJwtFilter} 的未登录文案保持一致 */
    private static final String UNAUTHORIZED_MESSAGE = "未登录或登录已过期";

    /** 允许注入的类型白名单（其余一律报错） */
    private static final String SUPPORTED_TYPES = "Long/long、AppSession、AppUserProfile";

    /** 用 ObjectProvider 是为了容忍「没装 qtools-app」的场景（单模块测试 / 只装媒体模块） */
    private final ObjectProvider<IAppTokenVerifier> appTokenVerifier;

    public CurrentAppUserArgumentResolver(ObjectProvider<IAppTokenVerifier> appTokenVerifier)
    {
        this.appTokenVerifier = appTokenVerifier;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter)
    {
        return parameter.hasParameterAnnotation(CurrentAppUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory)
    {
        CurrentAppUser annotation = parameter.getParameterAnnotation(CurrentAppUser.class);
        boolean required = annotation == null || annotation.required();

        Long userId = null;
        String jti = null;
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request != null)
        {
            Object idAttribute = request.getAttribute(AppRequestAttributes.USER_ID);
            if (idAttribute instanceof Long)
            {
                userId = (Long) idAttribute;
            }
            Object tokenAttribute = request.getAttribute(AppRequestAttributes.TOKEN_ID);
            if (tokenAttribute instanceof String)
            {
                jti = (String) tokenAttribute;
            }
            if (userId == null)
            {
                userId = verify(bearerToken(request));
            }
        }
        if (userId == null)
        {
            if (!required)
            {
                return null;
            }
            throw new AppUnauthorizedException(UNAUTHORIZED_MESSAGE);
        }

        Class<?> type = parameter.getParameterType();
        if (Long.class.equals(type) || long.class.equals(type))
        {
            return userId;
        }
        if (AppSession.class.equals(type))
        {
            return new AppSession(userId, jti);
        }
        if (AppUserProfile.class.equals(type))
        {
            return profileOf(userId);
        }
        throw new IllegalStateException("@CurrentAppUser 不支持参数类型 " + type.getName() + "（"
                + parameter.getExecutable() + "）：只支持 " + SUPPORTED_TYPES);
    }

    /** 回落路径：拿原始 token 换账号 id（SPI 缺失或有任何校验失败都只是「没登录」） */
    private Long verify(String token)
    {
        if (token == null)
        {
            return null;
        }
        IAppTokenVerifier verifier = appTokenVerifier == null ? null : appTokenVerifier.getIfAvailable();
        return verifier == null ? null : verifier.verifyUserId(token);
    }

    /** 档案查不到时给 null（那是「没有档案」，不是「没登录」，不该报 401） */
    private AppUserProfile profileOf(Long userId)
    {
        IAppTokenVerifier verifier = appTokenVerifier == null ? null : appTokenVerifier.getIfAvailable();
        return verifier == null ? null : verifier.profileOf(userId);
    }

    /** 取 {@code Authorization: Bearer <token>}；缺失或方案不对返回 null */
    private static String bearerToken(HttpServletRequest request)
    {
        String header = request.getHeader("Authorization");
        if (header == null)
        {
            return null;
        }
        header = header.trim();
        if (header.length() <= BEARER.length() || !header.regionMatches(true, 0, BEARER, 0, BEARER.length()))
        {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
