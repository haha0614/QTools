package com.qtools.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JWT <b>载荷偷看</b>（不验签）：只用来判断「这个 token 是不是用户端 app token」
 *
 * <p>为什么需要：后台（若依）与用户端两套身份共用 {@code Authorization} 头。后台的
 * {@code JwtAuthenticationTokenFilter} 对<b>每个</b>请求都会拿头里的 token 去用后台密钥验签，
 * 于是用户端请求（app token，{@code scope=app}、{@code iss=qtools-app}、另一把密钥）必然验签失败，
 * 每个请求在 {@code TokenService#getLoginUser} 里刷一条 ERROR —— 功能没坏，纯日志噪声。
 * 这里在解析前先看一眼 payload 声明，是用户端 token 就跳过。</p>
 *
 * <p><b>安全边界（重要）</b>：本类返回的内容<b>未经签名校验</b>，任何人都能伪造。因此它只允许
 * 用于「跳过无意义的工作」（省一次必然失败的验签 + 一行噪声日志），
 * <b>绝不能</b>用它做任何授权、身份或权限判断 —— 谁能进哪个接口，仍由各自验签通过的链路决定。</p>
 *
 * @author qtools
 */
public final class JwtPeek
{
    /** 用户端 token 的 issuer（{@code qtools.app.jwt.issuer} 的默认值） */
    public static final String APP_ISSUER = "qtools-app";

    /** 用户端 token 的 scope（{@code AppJwtService.SCOPE_APP}） */
    public static final String APP_SCOPE = "app";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtPeek()
    {
    }

    /**
     * 是不是用户端 app token（{@code scope=app} 或 {@code iss=qtools-app}）
     *
     * @param token 原始 token（不带 {@code Bearer }）；null / 格式非法一律返回 false
     */
    public static boolean isAppToken(String token)
    {
        Map<String, Object> claims = claimsOf(token);
        if (claims == null)
        {
            return false;
        }
        Object scope = claims.get("scope");
        if (scope != null && APP_SCOPE.equals(String.valueOf(scope)))
        {
            return true;
        }
        Object issuer = claims.get("iss");
        return issuer != null && APP_ISSUER.equals(String.valueOf(issuer));
    }

    /**
     * 取 payload 声明（<b>不验签</b>）
     *
     * @return 解析失败 / 不是三段式 / payload 不是 JSON 对象时返回 null
     */
    public static Map<String, Object> claimsOf(String token)
    {
        if (token == null || token.isEmpty())
        {
            return null;
        }
        // JWS 紧凑格式固定三段（header.payload.signature）：段数不对就不是我们要偷看的东西
        String[] parts = token.split("\\.");
        if (parts.length != 3 || parts[1].isEmpty())
        {
            return null;
        }
        try
        {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            Object parsed = MAPPER.readValue(new String(payload, StandardCharsets.UTF_8), Object.class);
            if (!(parsed instanceof Map))
            {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = (Map<String, Object>) parsed;
            return claims;
        }
        catch (Exception e)
        {
            // 不是 JWT / base64 坏了 / payload 不是 JSON：当"不是 app token"，交给原来的验签逻辑去判断
            return null;
        }
    }
}
