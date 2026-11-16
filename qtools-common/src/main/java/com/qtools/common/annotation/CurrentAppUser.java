package com.qtools.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入「当前用户端账号」（表 {@code app_user}）到控制器方法参数
 *
 * <p>有了它，控制器里不必再写 {@code AppJwtFilter.currentUserId(request)} / {@code currentTokenId(request)}
 * 那一套解析：加注解即得。解析由 {@code com.qtools.framework.web.resolver.CurrentAppUserArgumentResolver} 完成，
 * 支持三种参数类型：</p>
 *
 * <ul>
 *   <li>{@code Long} / {@code long} —— {@code app_user.id}；</li>
 *   <li>{@link com.qtools.common.security.AppSession} —— id + jti（登出要撤销「当前这一个会话」时用 jti）；</li>
 *   <li>{@link com.qtools.common.security.AppUserProfile} —— role + maxRating（一次主键点查，不拖 avatar 大字段）。</li>
 * </ul>
 *
 * <p><b>成本</b>：在 {@code /api/app/**} 上是零 —— {@code AppJwtFilter} 已经把 id / jti 放进 request attribute，
 * 解析器只读不查、不重复验签；在其它路径（例如 {@code /api/media/**} 那些不走该过滤器的接口）才回落到
 * {@link com.qtools.common.security.IAppTokenVerifier} 验一次 token，且这条路径拿不到 jti（为 {@code null}）。</p>
 *
 * <p><b>未登录</b>：{@link #required()} 为 true（默认）时抛
 * {@link com.qtools.common.exception.AppUnauthorizedException}，成品是
 * {@code 401 + {"error":"未登录或登录已过期"}}；为 false 时注入 {@code null}（媒体站匿名态接口用）。</p>
 *
 * @author qtools
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentAppUser
{
    /**
     * 未登录时是否报错：true（默认）= 401；false = 注入 null
     */
    boolean required() default true;
}
