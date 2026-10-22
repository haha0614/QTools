package com.qtools.app.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.qtools.app.config.AppProperties;
import com.qtools.app.mapper.AppUserTokenMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/**
 * 用户端 JWT 签发与校验（独立密钥 + 独立 claim 约定）
 *
 * <p>claim 约定：{@code sub=<appUserId>}、{@code jti}、{@code scope=app}、{@code iss=qtools-app}。
 * 与后台 token 的隔离靠三重差异：<b>密钥不同</b>（{@code qtools.app.jwt.secret} vs {@code token.secret}）、
 * <b>scope 不同</b>（后台 token 无 scope=app）、<b>iss 不同</b>。任一不满足即视为未登录。</p>
 *
 * <p>启动即校验密钥：为空、短于 32 字节、或与后台 {@code token.secret} 相同都直接抛异常，
 * 让错误暴露在启动阶段而不是第一次登录。</p>
 *
 * @author qtools
 */
@Component
public class AppJwtService
{
    /** 用户端 token 的 scope 值（后台 token 没有该 claim） */
    public static final String SCOPE_APP = "app";

    /** HS256 最小密钥长度（字节） */
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] keyBytes;

    private final String issuer;

    private final int expireDays;

    @Autowired
    private AppUserTokenMapper appUserTokenMapper;

    public AppJwtService(AppProperties properties, @Value("${token.secret:}") String backendTokenSecret)
    {
        AppProperties.Jwt jwt = properties.getJwt();
        String secret = jwt.getSecret();
        if (secret == null || secret.isBlank())
        {
            throw new IllegalStateException(
                    "qtools.app.jwt.secret 未配置：用户端 token 必须使用独立密钥（secret.yml 的 qtools.app.jwt.secret）");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES)
        {
            throw new IllegalStateException(
                    "qtools.app.jwt.secret 太短（" + bytes.length + " 字节）：HS256 至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        if (backendTokenSecret != null && !backendTokenSecret.isBlank() && secret.equals(backendTokenSecret))
        {
            throw new IllegalStateException(
                    "qtools.app.jwt.secret 不能与后台 token.secret 相同：后台与用户端两套身份必须互不承认");
        }
        this.keyBytes = bytes;
        this.issuer = jwt.getIssuer() == null || jwt.getIssuer().isBlank() ? "qtools-app" : jwt.getIssuer().trim();
        this.expireDays = jwt.getExpireDays() <= 0 ? 7 : jwt.getExpireDays();
    }

    /** 本次签发的会话标识（jti）与过期时间 */
    public static class Issued
    {
        private final String token;

        private final String jti;

        private final LocalDateTime expiresAt;

        private final long expiresInSeconds;

        Issued(String token, String jti, LocalDateTime expiresAt, long expiresInSeconds)
        {
            this.token = token;
            this.jti = jti;
            this.expiresAt = expiresAt;
            this.expiresInSeconds = expiresInSeconds;
        }

        public String getToken()
        {
            return token;
        }

        public String getJti()
        {
            return jti;
        }

        public LocalDateTime getExpiresAt()
        {
            return expiresAt;
        }

        public long getExpiresInSeconds()
        {
            return expiresInSeconds;
        }
    }

    /** 校验通过后的上下文（filter 写入 request attribute） */
    public static class Principal
    {
        private final Long userId;

        private final String jti;

        Principal(Long userId, String jti)
        {
            this.userId = userId;
            this.jti = jti;
        }

        public Long getUserId()
        {
            return userId;
        }

        public String getJti()
        {
            return jti;
        }
    }

    /** 签发 token（不落库，调用方负责写 app_user_token） */
    public Issued issue(Long userId)
    {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expireDays * 86400L);
        String token = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setId(jti)
                .setIssuer(issuer)
                .claim("scope", SCOPE_APP)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(SignatureAlgorithm.HS256, keyBytes)
                .compact();
        LocalDateTime expiresAt = LocalDateTime.ofInstant(exp, ZoneId.systemDefault());
        return new Issued(token, jti, expiresAt, expireDays * 86400L);
    }

    /**
     * 校验 token：签名 + iss + scope + 未过期 + 数据库会话未撤销未过期
     *
     * @return 校验通过返回主体；任何一项不满足返回 null（不抛异常，由 filter 统一回 401）
     */
    public Principal verify(String token)
    {
        Claims claims;
        try
        {
            claims = Jwts.parser().setSigningKey(keyBytes).parseClaimsJws(token).getBody();
        }
        catch (Exception e)
        {
            // 签名错误 / 后台 token（密钥不同）/ 已过期 / 格式非法 全部落到这里
            return null;
        }
        if (claims == null || !issuer.equals(claims.getIssuer()))
        {
            return null;
        }
        if (!SCOPE_APP.equals(claims.get("scope")))
        {
            return null;
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank())
        {
            return null;
        }
        Long userId;
        try
        {
            userId = Long.valueOf(claims.getSubject());
        }
        catch (Exception e)
        {
            return null;
        }
        // 会话表是撤销的唯一权威：登出后同一个 token 必须立刻失效
        if (appUserTokenMapper.countValidJti(jti) <= 0)
        {
            return null;
        }
        return new Principal(userId, jti);
    }
}
