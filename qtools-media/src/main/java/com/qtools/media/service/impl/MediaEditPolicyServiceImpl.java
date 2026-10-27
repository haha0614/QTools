package com.qtools.media.service.impl;

import java.util.LinkedHashSet;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.qtools.common.security.IAppRoleResolver;
import com.qtools.common.security.IAppTokenVerifier;
import com.qtools.media.exception.MediaException;
import com.qtools.media.mapper.MediaEditPolicyMapper;
import com.qtools.media.service.IMediaEditPolicyService;
import com.qtools.media.support.BearerTokens;

/**
 * 媒体库「站端编辑策略」服务实现：三档 off / local / full
 *
 * <p>读取路径：sys_config['media.userEditing'] + 30s 本地缓存（{@code volatile} 双字段，
 * 单实例自托管场景不需要更重的并发结构）；写入路径：{@link #setLevel} 先 insert-if-absent
 * 再 update，写完立刻清缓存，所以「改完马上生效」。</p>
 *
 * <p>非法值（拼错、空串、NULL）一律<b>回退 full</b>并只打一条 warn：默认档位是 full，
 * 一个损坏的配置不该把自托管实例的站端编辑锁死。</p>
 *
 * @author qtools
 */
@Service
public class MediaEditPolicyServiceImpl implements IMediaEditPolicyService
{
    private static final Logger log = LoggerFactory.getLogger(MediaEditPolicyServiceImpl.class);

    /** sys_config 键名（唯一数据源） */
    public static final String CONFIG_KEY = "media.userEditing";

    public static final String LEVEL_OFF = "off";

    public static final String LEVEL_LOCAL = "local";

    public static final String LEVEL_FULL = "full";

    /** 合法档位（顺序即错误提示里的顺序） */
    public static final Set<String> LEVELS;

    static
    {
        Set<String> levels = new LinkedHashSet<>();
        levels.add(LEVEL_OFF);
        levels.add(LEVEL_LOCAL);
        levels.add(LEVEL_FULL);
        LEVELS = java.util.Collections.unmodifiableSet(levels);
    }

    /** 非 full 时站端写接口的 403 文案（前端直接展示，因此定稿在这里） */
    public static final String BLOCKED_MESSAGE = "当前为演示/本地模式，站端不允许写入（管理员可在媒体库「编辑策略」中改为完全编辑）";

    /** 本地缓存有效期：30s 内同一档位不查库 */
    private static final long CACHE_TTL_MS = 30_000L;

    @Autowired
    private MediaEditPolicyMapper mediaEditPolicyMapper;

    /**
     * 用户端 token 校验 / 角色解析（都由 qtools-app 提供实现）
     *
     * <p>用 {@code ObjectProvider} 而不是直接 {@code @Autowired}：媒体模块单独部署或跑单测时
     * 这两个 bean 不存在，{@code getIfAvailable()} 返回 null → 一切按「未登录 / 普通用户」处理，
     * 不会启动失败（与 {@code MediaOwnerServiceImpl} 同一个写法）。</p>
     */
    @Autowired
    private ObjectProvider<IAppTokenVerifier> appTokenVerifier;

    @Autowired
    private ObjectProvider<IAppRoleResolver> appRoleResolver;

    /** 缓存值（已归一化，一定是三个合法值之一） */
    private volatile String cachedLevel;

    /** 缓存写入时刻（System.currentTimeMillis） */
    private volatile long cachedAt;

    @Override
    public String getLevel()
    {
        long now = System.currentTimeMillis();
        String level = cachedLevel;
        if (level != null && now - cachedAt < CACHE_TTL_MS)
        {
            return level;
        }
        String resolved = resolve(mediaEditPolicyMapper.selectValueByKey(CONFIG_KEY));
        cachedLevel = resolved;
        cachedAt = now;
        return resolved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setLevel(String level)
    {
        String clean = trimToNull(level);
        if (clean == null || !LEVELS.contains(clean))
        {
            throw MediaException.badRequest("level 只能是 " + String.join(" / ", LEVELS) + "，本次: " + level);
        }
        // 没有唯一索引，只能先补行再覆盖：两步都是幂等的
        mediaEditPolicyMapper.insertIfAbsent(CONFIG_KEY, clean);
        mediaEditPolicyMapper.updateValue(CONFIG_KEY, clean);
        invalidate();
        log.info("媒体库站端编辑策略改为 {}", clean);
    }

    /**
     * 站端写闸门（P5.89 起<b>唯一</b>的角色判定点）
     *
     * <p>顺序刻意是「先问角色、再读策略」：站长命中时连 {@code sys_config} 都不查（省一次库），
     * 且不受 30s 策略缓存影响 —— 管理员把档位改回 off 时，站长照样能改人物头像 / 背景。</p>
     *
     * <p>为什么放在这里而不是每个 controller：站端 20+ 个写接口第一行都是这一句
     * （{@code MediaEditController} / {@code MediaFilterController} / {@code MediaPlaylistController}），
     * 角色逻辑写在实现里就只有一份，controller 一行都不用改。</p>
     */
    @Override
    public String requireWritable()
    {
        if (isCurrentOwner())
        {
            return getLevel();
        }
        String level = getLevel();
        if (!LEVEL_FULL.equals(level))
        {
            throw new MediaException(403, BLOCKED_MESSAGE);
        }
        return level;
    }

    @Override
    public String currentRole()
    {
        Long userId = currentUserId();
        if (userId == null)
        {
            return null;
        }
        IAppRoleResolver resolver = appRoleResolver == null ? null : appRoleResolver.getIfAvailable();
        return resolver == null ? null : resolver.roleOf(userId);
    }

    @Override
    public boolean canWrite()
    {
        return isCurrentOwner() || LEVEL_FULL.equals(getLevel());
    }

    /** 当前请求者是否站长（未登录 / 没有角色解析组件一律 false） */
    boolean isCurrentOwner()
    {
        return IAppRoleResolver.ROLE_OWNER.equals(currentRole());
    }

    /**
     * 当前请求的登录账号 id —— <b>不读 controller 传进来的参数</b>，直接取线程绑定的请求
     *
     * <p>{@link RequestContextHolder} 是 Spring MVC 在派发时绑定的（同一个线程），因此
     * {@code requireWritable()} 无需改签名、无需 controller 传参就能知道「这次请求是谁」。
     * 非 HTTP 场景（单测 / 定时任务）取不到请求 → 返回 null → 按未登录处理，行为与 P5.89 之前一致。</p>
     */
    private Long currentUserId()
    {
        HttpServletRequest request = currentRequest();
        if (request == null)
        {
            return null;
        }
        IAppTokenVerifier verifier = appTokenVerifier == null ? null : appTokenVerifier.getIfAvailable();
        if (verifier == null)
        {
            return null;
        }
        String token = BearerTokens.of(request);
        if (token == null)
        {
            return null;
        }
        try
        {
            return verifier.verifyUserId(token);
        }
        catch (RuntimeException e)
        {
            log.debug("站端闸门解析登录态异常，按未登录处理: {}", e.getMessage());
            return null;
        }
    }

    private static HttpServletRequest currentRequest()
    {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet)
        {
            return servlet.getRequest();
        }
        return null;
    }

    /** 清空本地缓存（写后调用；30s 后自然过期，因此外部改库最迟 30s 生效） */
    public void invalidate()
    {
        cachedLevel = null;
        cachedAt = 0L;
    }

    /** 库里的值 → 合法档位；非法 / 缺失回退 full */
    private static String resolve(String raw)
    {
        String clean = trimToNull(raw);
        if (clean != null)
        {
            String lower = clean.toLowerCase();
            if (LEVELS.contains(lower))
            {
                return lower;
            }
            log.warn("sys_config.{} 的值非法（{}），回退 {}", CONFIG_KEY, raw, LEVEL_FULL);
        }
        return LEVEL_FULL;
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
