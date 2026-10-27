package com.qtools.app.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.qtools.app.domain.AppUser;
import com.qtools.app.mapper.AppUserMapper;

/**
 * 用户端账号「审核状态」判定（P5.79）
 *
 * <p><b>唯一真源</b>：注册写入什么状态、登录拒绝的文案、{@link AppJwtFilter} 每请求的拦截、
 * {@link AppTokenVerifier}（媒体模块验 token）的状态校验，全部调用本类，避免同一套
 * pending / rejected 文案散在各处 if 里漂移。</p>
 *
 * <p>口径（P5.81 起先看封禁、再看审核态）：</p>
 * <ul>
 *   <li>{@code banned = 1} = 已封禁 → 「账号已被封禁」+（有原因时）「：<banned_reason>」，
 *       与审核态无关（审核通过但被封禁同样进不来；解封后回到原审核态的判定）；</li>
 *   <li>{@code approved}（或历史上为空）= 放行；</li>
 *   <li>{@code pending} = 待审核 → 「账号待审核，请联系站长」；</li>
 *   <li>{@code rejected} = 未通过 → 「注册未通过审核」+ 审核备注（有则带上）；</li>
 *   <li>其它未知值 = 保守拒绝（不认识的状态一律不进站）。</li>
 * </ul>
 *
 * @author qtools
 */
@Component
public class AppUserGuard
{
    /** 待审核 */
    public static final String STATUS_PENDING = "pending";

    /** 已通过（注册默认值；迁移把存量 0 映射到这里） */
    public static final String STATUS_APPROVED = "approved";

    /** 未通过 */
    public static final String STATUS_REJECTED = "rejected";

    public static final String MSG_PENDING = "账号待审核，请联系站长";

    public static final String MSG_REJECTED = "注册未通过审核";

    public static final String MSG_UNKNOWN = "账号状态异常，请联系站长";

    public static final String MSG_MISSING = "账号不存在或已停用";

    /** P5.81：封禁（独立于审核态，见 doc/app_user_ban.sql 的说明） */
    public static final String MSG_BANNED = "账号已被封禁";

    /** 管理端列表筛选值「已封禁」：不是审核状态，仅作为查询条件（banned = 1） */
    public static final String FILTER_BANNED = "banned";

    @Autowired
    private AppUserMapper appUserMapper;

    /** 是否为合法审核状态值 */
    public static boolean isKnownStatus(String status)
    {
        return STATUS_PENDING.equals(status) || STATUS_APPROVED.equals(status) || STATUS_REJECTED.equals(status);
    }

    /**
     * 按 user_id 判定（鉴权过滤器、媒体 token 校验走这条：只查 status / review_note 三列）
     *
     * @return {@code null} 表示放行；非 null 是给用户看的拒绝文案
     */
    public String blockReasonOf(Long userId)
    {
        if (userId == null)
        {
            return MSG_MISSING;
        }
        return blockReason(appUserMapper.selectStatusById(userId));
    }

    /**
     * 按整行账号判定（登录流程手上已有整行，不再多查一次）
     *
     * @return {@code null} 表示可继续登录；非 null 是拒绝文案
     */
    public String blockReason(AppUser user)
    {
        if (user == null)
        {
            return MSG_MISSING;
        }
        // P5.81：封禁优先于审核态 —— 这是管理员明确的处置动作，文案要一眼看出「被封了」而不是「待审核」
        if (user.isBanned())
        {
            String reason = user.getBannedReason();
            return reason == null || reason.isBlank() ? MSG_BANNED : MSG_BANNED + "：" + reason.trim();
        }
        String status = user.getStatus();
        if (status == null || status.isEmpty() || STATUS_APPROVED.equals(status))
        {
            return null;
        }
        if (STATUS_PENDING.equals(status))
        {
            return MSG_PENDING;
        }
        if (STATUS_REJECTED.equals(status))
        {
            String note = user.getReviewNote();
            return note == null || note.isBlank() ? MSG_REJECTED : MSG_REJECTED + "：" + note.trim();
        }
        return MSG_UNKNOWN;
    }
}
