package com.qtools.app.service.impl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.app.domain.AppUser;
import com.qtools.app.exception.AppException;
import com.qtools.app.mapper.AppUserMapper;
import com.qtools.app.security.AppUserGuard;
import com.qtools.app.service.IAppUserAdminService;
import com.qtools.common.core.page.TableDataInfo;

/**
 * 用户端账号 审核管理实现（P5.79）
 *
 * <p>不写 sys_user，也不复用若依的 SysUserService：用户端账号是独立表、独立身份体系，
 * 管理端在这里只做「看列表 + 改审核状态」两件事。</p>
 *
 * @author qtools
 */
@Service
public class AppUserAdminServiceImpl implements IAppUserAdminService
{
    /** 单页上限：防止 size=100000 拉全表 */
    private static final int SIZE_MAX = 100;

    private static final int SIZE_DEFAULT = 10;

    private static final int KEYWORD_MAX = 64;

    private static final int NOTE_MAX = 255;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private AppUserMapper appUserMapper;

    @Override
    public TableDataInfo list(String status, String keyword, Integer page, Integer size)
    {
        String statusValue = normalizeStatus(status);
        String keywordValue = trim(keyword, KEYWORD_MAX);
        int pageValue = page == null || page < 1 ? 1 : page;
        int sizeValue = size == null || size < 1 ? SIZE_DEFAULT : Math.min(size, SIZE_MAX);

        int total = appUserMapper.countPage(statusValue, keywordValue);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (total > 0)
        {
            int offset = (pageValue - 1) * sizeValue;
            if (offset < total)
            {
                for (AppUser user : appUserMapper.selectPage(statusValue, keywordValue, offset, sizeValue))
                {
                    rows.add(adminView(user));
                }
            }
        }
        return new TableDataInfo(rows, total);
    }

    @Override
    public Map<String, Object> stats()
    {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put(AppUserGuard.STATUS_PENDING, 0L);
        counts.put(AppUserGuard.STATUS_APPROVED, 0L);
        counts.put(AppUserGuard.STATUS_REJECTED, 0L);
        long total = 0L;
        for (Map<String, Object> row : appUserMapper.selectStatusCounts())
        {
            Object key = row.get("status");
            Object value = row.get("cnt");
            long count = value instanceof Number ? ((Number) value).longValue() : 0L;
            counts.put(key == null ? "unknown" : String.valueOf(key), count);
            total += count;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", counts.getOrDefault(AppUserGuard.STATUS_PENDING, 0L));
        result.put("approved", counts.getOrDefault(AppUserGuard.STATUS_APPROVED, 0L));
        result.put("rejected", counts.getOrDefault(AppUserGuard.STATUS_REJECTED, 0L));
        result.put("total", total);
        return result;
    }

    @Override
    public Map<String, Object> detail(Long id)
    {
        return adminView(requireUser(id));
    }

    @Override
    public Map<String, Object> review(Long id, String status, String note, Long operatorId)
    {
        AppUser user = requireUser(id);
        String target = requireStatus(status);
        // 只有「未通过」才保留原因；通过 / 撤销时清掉旧原因，避免表格里留着过期红字
        String noteValue = AppUserGuard.STATUS_REJECTED.equals(target) ? trim(note, NOTE_MAX) : null;

        boolean sameStatus = target.equals(user.getStatus());
        boolean sameNote = noteValue == null ? user.getReviewNote() == null : noteValue.equals(user.getReviewNote());
        if (sameStatus && sameNote)
        {
            Map<String, Object> unchanged = adminView(user);
            unchanged.put("changed", false);
            return unchanged;
        }
        appUserMapper.updateReviewStatus(id, target, noteValue, operatorId);
        Map<String, Object> changed = adminView(appUserMapper.selectById(id));
        changed.put("changed", true);
        return changed;
    }

    @Override
    public Map<String, Object> batchApprove(List<Long> ids, Long operatorId)
    {
        if (ids == null || ids.isEmpty())
        {
            throw AppException.badRequest("ids 不能为空");
        }
        if (ids.size() > SIZE_MAX)
        {
            throw AppException.badRequest("一次最多处理 " + SIZE_MAX + " 个账号");
        }
        int changed = 0;
        List<Long> missing = new ArrayList<>();
        for (Long id : ids)
        {
            if (id == null)
            {
                continue;
            }
            AppUser user = appUserMapper.selectById(id);
            if (user == null)
            {
                missing.add(id);
                continue;
            }
            if (AppUserGuard.STATUS_APPROVED.equals(user.getStatus()))
            {
                continue;
            }
            appUserMapper.updateReviewStatus(id, AppUserGuard.STATUS_APPROVED, null, operatorId);
            changed++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("requested", ids.size());
        result.put("changed", changed);
        result.put("missing", missing);
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private AppUser requireUser(Long id)
    {
        AppUser user = id == null ? null : appUserMapper.selectById(id);
        if (user == null)
        {
            throw new AppException(404, "账号不存在");
        }
        return user;
    }

    /** query 参数里的 status：空 = 全部；非法值直接 400（不静默当成全部，否则筛选写错会看不出） */
    private static String normalizeStatus(String status)
    {
        String value = trim(status, 16);
        if (value == null)
        {
            return null;
        }
        if (!AppUserGuard.isKnownStatus(value))
        {
            throw AppException.badRequest("status 只能是 pending / approved / rejected");
        }
        return value;
    }

    private static String requireStatus(String status)
    {
        String value = trim(status, 16);
        if (value == null || !AppUserGuard.isKnownStatus(value))
        {
            throw AppException.badRequest("status 只能是 pending / approved / rejected");
        }
        return value;
    }

    /** 管理端视图：给管理看审核需要的字段；绝不带 passwordHash，也不带 avatar（MEDIUMTEXT 太大） */
    static Map<String, Object> adminView(AppUser user)
    {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("status", user.getStatus());
        view.put("statusText", statusText(user.getStatus()));
        view.put("inviteCode", user.getInviteCode());
        view.put("registerIp", user.getRegisterIp());
        view.put("createdAt", format(user.getCreatedAt()));
        view.put("lastLoginAt", format(user.getLastLoginAt()));
        view.put("reviewNote", user.getReviewNote());
        view.put("reviewedAt", format(user.getReviewedAt()));
        view.put("reviewedBy", user.getReviewedBy());
        view.put("hasPassword", user.getPasswordHash() != null && !user.getPasswordHash().isEmpty());
        return view;
    }

    static String statusText(String status)
    {
        if (AppUserGuard.STATUS_PENDING.equals(status))
        {
            return "待审核";
        }
        if (AppUserGuard.STATUS_REJECTED.equals(status))
        {
            return "未通过";
        }
        if (AppUserGuard.STATUS_APPROVED.equals(status))
        {
            return "已通过";
        }
        return "未知";
    }

    private static String format(LocalDateTime value)
    {
        return value == null ? null : value.format(DATE_TIME);
    }

    private static String trim(String value, int max)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty())
        {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
