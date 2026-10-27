package com.qtools.app.service.impl;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.qtools.app.domain.AppUser;
import com.qtools.app.domain.AppUserIdentity;
import com.qtools.app.exception.AppException;
import com.qtools.app.mapper.AppUserIdentityMapper;
import com.qtools.app.mapper.AppUserMapper;
import com.qtools.app.mapper.AppUserTokenMapper;
import com.qtools.app.security.AppRoleResolver;
import com.qtools.app.security.AppUserGuard;
import com.qtools.app.service.IAppUserAdminService;
import com.qtools.common.core.page.TableDataInfo;
import com.qtools.common.security.IAppRoleResolver;

/**
 * 用户端账号 用户管理实现（P5.79 审核 → P5.81 完整用户管理）
 *
 * <p>不写 sys_user，也不复用若依的 SysUserService：用户端账号是独立表、独立身份体系。</p>
 *
 * <p>两处刻意的「一个真源」：</p>
 * <ul>
 *   <li>能不能进站只由 {@link AppUserGuard} 裁决（本类只负责写数据，不复制拦截逻辑）；</li>
 *   <li>密码哈希只在这里用 BCrypt(cost=10) 生成，与 {@code AppAuthServiceImpl} 同参数，
 *       重置后的密码走既有登录校验路径，不需要登录侧任何改动。</li>
 * </ul>
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

    private static final int NICKNAME_MAX = 64;

    private static final int REASON_MAX = 255;

    /** 与 app_schema.sql 的 avatar 注释一致（512KB） */
    private static final int AVATAR_MAX = 512 * 1024;

    private static final int PASSWORD_MIN = 8;

    /** BCrypt 只取前 72 字节，超长直接拒绝（与注册同口径） */
    private static final int PASSWORD_MAX = 64;

    private static final int PASSWORD_GENERATED_LEN = 12;

    /** 去掉了 0/O/1/l/I 这类易混字符：生成出来的密码要能被人抄 */
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 计数口径说明（给管理端详情用；媒体表按 owner_key 归属到账号） */
    private static final String COUNTS_NOTE = "favoriteCount = media_owner.user_id 关联 media_favorite 的行数；"
            + "playlistCount = 关联 media_playlist 的行数；媒体库的视频没有归属账号，videoCount 恒为 null（不是 0）";

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private AppUserTokenMapper appUserTokenMapper;

    @Autowired
    private AppUserIdentityMapper appUserIdentityMapper;

    /** 角色缓存（P5.89）：改完角色立刻失效，不让站长权限等 30s */
    @Autowired
    private AppRoleResolver appRoleResolver;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    /** 媒体表是否在本库（首次用到时查一次，之后缓存；缺席时计数一律 null 而不是让整页报错） */
    private volatile Boolean mediaOwnerTable;

    private volatile Boolean mediaFavoriteTable;

    private volatile Boolean mediaPlaylistTable;

    @Override
    public TableDataInfo list(String status, String keyword, Integer page, Integer size)
    {
        String statusFilter = normalizeFilterStatus(status);
        String statusValue = AppUserGuard.FILTER_BANNED.equals(statusFilter) ? null : statusFilter;
        Integer bannedValue = bannedParamOf(statusFilter);
        String keywordValue = trim(keyword, KEYWORD_MAX);
        int pageValue = page == null || page < 1 ? 1 : page;
        int sizeValue = size == null || size < 1 ? SIZE_DEFAULT : Math.min(size, SIZE_MAX);

        int total = appUserMapper.countPage(statusValue, bannedValue, keywordValue);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (total > 0)
        {
            int offset = (pageValue - 1) * sizeValue;
            if (offset < total)
            {
                List<AppUser> users = appUserMapper.selectPage(statusValue, bannedValue, keywordValue, offset, sizeValue);
                Map<Long, Map<String, Long>> counts = mediaCounts(users);
                for (AppUser user : users)
                {
                    Map<String, Object> view = adminView(user);
                    putMediaCounts(view, user.getId(), counts);
                    rows.add(view);
                }
            }
        }
        return new TableDataInfo(rows, total);
    }

    @Override
    public Map<String, Object> stats()
    {
        Map<String, Object> row = appUserMapper.selectStats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", count(row, "total"));
        result.put("pending", count(row, "pending"));
        result.put("approved", count(row, "approved"));
        result.put("rejected", count(row, "rejected"));
        result.put("banned", count(row, "banned"));
        result.put("newToday", count(row, "newToday"));
        result.put("activeRecently", count(row, "activeRecently"));
        // 卡片口径说明：approved = 审核通过且未封禁；banned 是独立维度，可能与 pending / rejected 重叠
        result.put("approvedExcludesBanned", true);
        result.put("activeRecentlyDays", 7);
        return result;
    }

    @Override
    public Map<String, Object> detail(Long id)
    {
        AppUser user = requireUser(id);
        Map<String, Object> view = adminView(user);
        // 详情带 avatar（编辑资料要显示当前头像），列表不带：MEDIUMTEXT 可能有 512KB，不能出现在每行
        view.put("avatar", user.getAvatar());
        putMediaCounts(view, user.getId(), mediaCounts(Collections.singletonList(user)));
        view.put("countsNote", COUNTS_NOTE);
        List<Map<String, Object>> identities = new ArrayList<>();
        for (AppUserIdentity identity : appUserIdentityMapper.selectByUserId(id))
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", identity.getType());
            item.put("identifier", identity.getIdentifier());
            item.put("verified", identity.getVerified());
            item.put("createdAt", format(identity.getCreatedAt()));
            identities.add(item);
        }
        view.put("identities", identities);
        return view;
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
        List<Long> idList = requireIds(ids);
        List<Long> missing = new ArrayList<>();
        List<Long> unchanged = new ArrayList<>();
        int changed = 0;
        for (Long id : idList)
        {
            AppUser user = appUserMapper.selectById(id);
            if (user == null)
            {
                missing.add(id);
                continue;
            }
            if (AppUserGuard.STATUS_APPROVED.equals(user.getStatus()))
            {
                unchanged.add(id);
                continue;
            }
            appUserMapper.updateReviewStatus(id, AppUserGuard.STATUS_APPROVED, null, operatorId);
            changed++;
        }
        return batchResult(idList.size(), changed, unchanged, missing);
    }

    @Override
    public Map<String, Object> updateProfile(Long id, Map<String, Object> body, Long operatorId)
    {
        AppUser user = requireUser(id);
        Map<String, Object> patch = new HashMap<>();
        patch.put("id", id);

        boolean setNickname = body != null && body.containsKey("nickname");
        String nickname = null;
        if (setNickname)
        {
            nickname = trim(text(body.get("nickname")), NICKNAME_MAX);
            if (nickname == null)
            {
                // nickname 是 NOT NULL 列：清空没有意义，直接拒绝而不是写空串
                throw AppException.badRequest("昵称不能为空");
            }
            patch.put("setNickname", true);
            patch.put("nickname", nickname);
        }

        boolean setAvatar = body != null && body.containsKey("avatar");
        String avatar = null;
        if (setAvatar)
        {
            avatar = text(body.get("avatar"));
            if (avatar != null && avatar.length() > AVATAR_MAX)
            {
                throw AppException.badRequest("头像过大（上限 512KB）");
            }
            patch.put("setAvatar", true);
            patch.put("avatar", avatar);
        }

        // 备注列名在接口上有三个别名：reviewNote（表字段原名）/ note / remark
        boolean setNote = body != null && (body.containsKey("reviewNote") || body.containsKey("note")
                || body.containsKey("remark"));
        String reviewNote = null;
        if (setNote)
        {
            Object raw = body.containsKey("reviewNote") ? body.get("reviewNote")
                    : (body.containsKey("note") ? body.get("note") : body.get("remark"));
            reviewNote = trim(text(raw), NOTE_MAX);
            patch.put("setReviewNote", true);
            patch.put("reviewNote", reviewNote);
        }

        // P5.89：角色（user / owner）也走这条改资料接口 —— 管理端「角色」列行内切换就调它
        boolean setRole = body != null && body.containsKey("role");
        String role = null;
        if (setRole)
        {
            role = requireRole(text(body.get("role")));
            // 「不能把最后一个 owner 降级」：唯一依据是库里 owner 的行数（不是前端传的标志）
            if (IAppRoleResolver.ROLE_USER.equals(role) && IAppRoleResolver.ROLE_OWNER.equals(roleOf(user))
                    && appUserMapper.countByRole(IAppRoleResolver.ROLE_OWNER) <= 1)
            {
                throw AppException.badRequest("不能把最后一个站长降级：请先把另一个账号设为站长，再降级这个账号");
            }
        }

        if (!setNickname && !setAvatar && !setNote && !setRole)
        {
            throw AppException.badRequest("没有要修改的字段（支持 nickname / avatar / reviewNote / role）");
        }

        boolean sameNickname = !setNickname || Objects.equals(nickname, user.getNickname());
        boolean sameAvatar = !setAvatar || Objects.equals(avatar, emptyToNull(user.getAvatar()));
        boolean sameNote = !setNote || Objects.equals(reviewNote, user.getReviewNote());
        boolean sameRole = !setRole || Objects.equals(role, roleOf(user));
        if (sameNickname && sameAvatar && sameNote && sameRole)
        {
            Map<String, Object> unchanged = adminView(user);
            unchanged.put("changed", false);
            return unchanged;
        }
        if (setRole && !sameRole)
        {
            appUserMapper.updateRole(id, role);
            // 角色缓存立刻失效：owner 的站端 full 权限不能等 30s 才生效
            appRoleResolver.invalidate(id);
        }
        if (setNickname || setAvatar || setNote)
        {
            appUserMapper.updateProfile(patch);
        }
        Map<String, Object> changed = adminView(appUserMapper.selectById(id));
        changed.put("changed", true);
        changed.put("updatedBy", operatorId);
        return changed;
    }

    @Override
    public Map<String, Object> resetPassword(Long id, String password, Long operatorId)
    {
        AppUser user = requireUser(id);
        String plain = text(password);
        boolean generated = false;
        if (plain == null)
        {
            plain = generatePassword();
            generated = true;
        }
        else
        {
            checkPasswordStrength(plain);
        }
        appUserMapper.updatePassword(id, passwordEncoder.encode(plain));
        // 旧密码立即失效的同时，旧会话也一起下线：否则「重置密码」对被踢下线前的人没有实际意义
        int revoked = appUserTokenMapper.revokeAllByUser(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("id", id);
        result.put("username", user.getUsername());
        result.put("generated", generated);
        result.put("sessionsRevoked", revoked);
        // 只回传一次：不落库、不写日志；管理页展示后不再可见
        result.put("password", generated ? plain : null);
        result.put("updateTime", format(appUserMapper.selectById(id).getUpdatedAt()));
        result.put("updatedBy", operatorId);
        return result;
    }

    @Override
    public Map<String, Object> ban(Long id, String reason, Long operatorId)
    {
        AppUser user = requireUser(id);
        String reasonValue = trim(reason, REASON_MAX);
        boolean sameReason = reasonValue == null ? user.getBannedReason() == null : reasonValue.equals(user.getBannedReason());
        if (user.isBanned() && sameReason)
        {
            Map<String, Object> unchanged = adminView(user);
            unchanged.put("changed", false);
            return unchanged;
        }
        appUserMapper.updateBan(id, true, reasonValue, LocalDateTime.now());
        // 刻意**不**撤销会话：撤销会让 token 在校验阶段就变成 401「登录已过期」，
        // 把「你被封禁了：<原因>」这条信息吞掉。封禁统一由 AppUserGuard 在过滤器里回 403 + 原因。
        Map<String, Object> changed = adminView(appUserMapper.selectById(id));
        changed.put("changed", true);
        changed.put("updatedBy", operatorId);
        return changed;
    }

    @Override
    public Map<String, Object> unban(Long id, Long operatorId)
    {
        AppUser user = requireUser(id);
        if (!user.isBanned())
        {
            Map<String, Object> unchanged = adminView(user);
            unchanged.put("changed", false);
            return unchanged;
        }
        appUserMapper.updateBan(id, false, null, null);
        Map<String, Object> changed = adminView(appUserMapper.selectById(id));
        changed.put("changed", true);
        changed.put("updatedBy", operatorId);
        return changed;
    }

    @Override
    public Map<String, Object> delete(Long id, String confirm, Long operatorId)
    {
        // 防误删：必须显式带 confirm=1（或 true），否则不删
        String confirmValue = text(confirm);
        if (!"1".equals(confirmValue) && !"true".equalsIgnoreCase(confirmValue))
        {
            throw AppException.badRequest("删除需要二次确认：请带 ?confirm=1");
        }
        AppUser user = requireUser(id);
        int tokens = appUserTokenMapper.deleteByUser(id);
        int identities = appUserIdentityMapper.deleteByUserId(id);
        boolean mediaUnbound = false;
        if (tableExists(1, "media_owner"))
        {
            appUserMapper.clearOwnerBinding(id);
            mediaUnbound = true;
        }
        appUserMapper.deleteById(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("id", id);
        // 返回里带被删除的用户名：事后对账 / 审计日志能直接看到删的是谁
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        Map<String, Object> cascaded = new LinkedHashMap<>();
        cascaded.put("tokens", tokens);
        cascaded.put("identities", identities);
        cascaded.put("mediaOwnerUnbound", mediaUnbound);
        result.put("cascaded", cascaded);
        result.put("updatedBy", operatorId);
        return result;
    }

    @Override
    public Map<String, Object> batchBan(List<Long> ids, String reason, Long operatorId)
    {
        List<Long> idList = requireIds(ids);
        String reasonValue = trim(reason, REASON_MAX);
        List<Long> missing = new ArrayList<>();
        List<Long> unchanged = new ArrayList<>();
        int changed = 0;
        for (Long id : idList)
        {
            AppUser user = appUserMapper.selectById(id);
            if (user == null)
            {
                missing.add(id);
                continue;
            }
            if (user.isBanned())
            {
                unchanged.add(id);
                continue;
            }
            appUserMapper.updateBan(id, true, reasonValue, LocalDateTime.now());
            // 同 ban()：不撤销会话，封禁文案由 AppUserGuard 在过滤器里给出
            changed++;
        }
        return batchResult(idList.size(), changed, unchanged, missing);
    }

    @Override
    public Map<String, Object> batchUnban(List<Long> ids, Long operatorId)
    {
        List<Long> idList = requireIds(ids);
        List<Long> missing = new ArrayList<>();
        List<Long> unchanged = new ArrayList<>();
        int changed = 0;
        for (Long id : idList)
        {
            AppUser user = appUserMapper.selectById(id);
            if (user == null)
            {
                missing.add(id);
                continue;
            }
            if (!user.isBanned())
            {
                unchanged.add(id);
                continue;
            }
            appUserMapper.updateBan(id, false, null, null);
            changed++;
        }
        return batchResult(idList.size(), changed, unchanged, missing);
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

    /** 批量接口共用：ids 非空 + 上限校验 */
    private static List<Long> requireIds(List<Long> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            throw AppException.badRequest("ids 不能为空");
        }
        if (ids.size() > SIZE_MAX)
        {
            throw AppException.badRequest("一次最多处理 " + SIZE_MAX + " 个账号");
        }
        List<Long> idList = new ArrayList<>();
        for (Long id : ids)
        {
            if (id != null)
            {
                idList.add(id);
            }
        }
        if (idList.isEmpty())
        {
            throw AppException.badRequest("ids 不能为空");
        }
        return idList;
    }

    private static Map<String, Object> batchResult(int requested, int changed, List<Long> unchanged, List<Long> missing)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("requested", requested);
        result.put("changed", changed);
        result.put("unchanged", unchanged);
        result.put("missing", missing);
        return result;
    }

    /** query 参数里的 status：空 = 全部；approved 表示「正常」（不含已封禁）；banned 表示「已封禁」 */
    private static String normalizeFilterStatus(String status)
    {
        String value = trim(status, 16);
        if (value == null)
        {
            return null;
        }
        if (AppUserGuard.FILTER_BANNED.equals(value))
        {
            return AppUserGuard.FILTER_BANNED;
        }
        if (!AppUserGuard.isKnownStatus(value))
        {
            throw AppException.badRequest("status 只能是 pending / approved / rejected / banned");
        }
        return value;
    }

    /** 「正常」= 审核通过且未封禁；「已封禁」= banned=1（与审核态无关，两者都不重叠） */
    private static Integer bannedParamOf(String statusFilter)
    {
        if (AppUserGuard.FILTER_BANNED.equals(statusFilter))
        {
            return 1;
        }
        if (AppUserGuard.STATUS_APPROVED.equals(statusFilter))
        {
            return 0;
        }
        return null;
    }

    /** 角色值域校验（P5.89）：只接受 user / owner，其它一律 400 */
    private static String requireRole(String role)
    {
        if (role == null)
        {
            throw AppException.badRequest("role 只能是 user / owner");
        }
        String clean = role.trim().toLowerCase();
        if (!IAppRoleResolver.ROLE_USER.equals(clean) && !IAppRoleResolver.ROLE_OWNER.equals(clean))
        {
            throw AppException.badRequest("role 只能是 user / owner，本次: " + role);
        }
        return clean;
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

    /** 管理端视图：给管理看管理需要的字段；绝不带 passwordHash */
    static Map<String, Object> adminView(AppUser user)
    {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("nickname", user.getNickname());
        view.put("status", user.getStatus());
        view.put("statusText", statusText(user.getStatus()));
        // P5.89：两档角色（user 普通用户 / owner 站长）—— 管理端列表「角色」列与行内切换读它
        view.put("role", roleOf(user));
        view.put("roleText", roleText(roleOf(user)));
        view.put("banned", user.isBanned());
        view.put("bannedReason", user.getBannedReason());
        view.put("bannedAt", format(user.getBannedAt()));
        // 徽标文案的唯一来源：封禁优先于审核态（与 AppUserGuard 的拦截口径一致）
        view.put("badgeText", user.isBanned() ? "已封禁" : statusText(user.getStatus()));
        view.put("inviteCode", user.getInviteCode());
        view.put("registerIp", user.getRegisterIp());
        view.put("createdAt", format(user.getCreatedAt()));
        view.put("lastLoginAt", format(user.getLastLoginAt()));
        // 别名：接口契约里叫 lastLoginTime，库里列名叫 last_login_at（同一份数据，两个键都给，避免前端猜）
        view.put("lastLoginTime", format(user.getLastLoginAt()));
        view.put("updateTime", format(user.getUpdatedAt()));
        view.put("reviewNote", user.getReviewNote());
        view.put("reviewedAt", format(user.getReviewedAt()));
        view.put("reviewedBy", user.getReviewedBy());
        view.put("hasPassword", user.getPasswordHash() != null && !user.getPasswordHash().isEmpty());
        return view;
    }

    /** 角色归一化（P5.89）：空 / 脏值一律普通用户 */
    static String roleOf(AppUser user)
    {
        if (user == null || user.getRole() == null)
        {
            return IAppRoleResolver.ROLE_USER;
        }
        String clean = user.getRole().trim().toLowerCase();
        return IAppRoleResolver.ROLE_OWNER.equals(clean) ? IAppRoleResolver.ROLE_OWNER
                : IAppRoleResolver.ROLE_USER;
    }

    /** 角色文案（管理端表格「角色」列） */
    static String roleText(String role)
    {
        return IAppRoleResolver.ROLE_OWNER.equals(role) ? "站长" : "普通用户";
    }

    static String statusText(String status)
    {
        if (AppUserGuard.STATUS_PENDING.equals(status))
        {
            return "待审核";
        }
        if (AppUserGuard.STATUS_REJECTED.equals(status))
        {
            return "已拒绝";
        }
        if (AppUserGuard.STATUS_APPROVED.equals(status))
        {
            return "正常";
        }
        return "未知";
    }

    /** 某张表在不在（每个表只在第一次用到时查一次） */
    private boolean tableExists(int slot, String tableName)
    {
        Boolean cached = slot == 1 ? mediaOwnerTable : (slot == 2 ? mediaFavoriteTable : mediaPlaylistTable);
        if (cached == null)
        {
            boolean present;
            try
            {
                present = appUserMapper.countTable(tableName) > 0;
            }
            catch (RuntimeException e)
            {
                present = false;
            }
            if (slot == 1)
            {
                mediaOwnerTable = present;
            }
            else if (slot == 2)
            {
                mediaFavoriteTable = present;
            }
            else
            {
                mediaPlaylistTable = present;
            }
            cached = present;
        }
        return Boolean.TRUE.equals(cached);
    }

    /** 收藏数 / 播放列表数一起算（都靠 media_owner.user_id 把 owner_key 归到账号） */
    private Map<Long, Map<String, Long>> mediaCounts(List<AppUser> users)
    {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        if (users == null || users.isEmpty() || !tableExists(1, "media_owner"))
        {
            return result;
        }
        List<Long> ids = new ArrayList<>();
        for (AppUser user : users)
        {
            if (user.getId() != null)
            {
                ids.add(user.getId());
            }
        }
        if (ids.isEmpty())
        {
            return result;
        }
        collect(result, () -> appUserMapper.selectFavoriteCounts(ids), "favoriteCount");
        collect(result, () -> appUserMapper.selectPlaylistCounts(ids), "playlistCount");
        return result;
    }

    private static void collect(Map<Long, Map<String, Long>> target, java.util.function.Supplier<List<Map<String, Object>>> query,
            String key)
    {
        try
        {
            for (Map<String, Object> row : query.get())
            {
                Object id = row.get("userId");
                Object value = row.get("cnt");
                if (id instanceof Number)
                {
                    long userId = ((Number) id).longValue();
                    target.computeIfAbsent(userId, k -> new HashMap<>()).put(key,
                            value instanceof Number ? ((Number) value).longValue() : 0L);
                }
            }
        }
        catch (RuntimeException e)
        {
            // 媒体表结构变动 / 权限不足不应该让「用户管理」整页失败：这一项计数留空
        }
    }

    private void putMediaCounts(Map<String, Object> view, Long userId, Map<Long, Map<String, Long>> counts)
    {
        boolean ownerPresent = tableExists(1, "media_owner");
        Map<String, Long> row = userId == null ? null : counts.get(userId);
        view.put("favoriteCount", ownerPresent && tableExists(2, "media_favorite") ? value(row, "favoriteCount") : null);
        view.put("playlistCount", ownerPresent && tableExists(3, "media_playlist") ? value(row, "playlistCount") : null);
        // 媒体库里的视频没有归属账号列：给 null（不知道）而不是 0（真的没有），两者语义不同
        view.put("videoCount", null);
    }

    private static Long value(Map<String, Long> row, String key)
    {
        if (row == null)
        {
            return 0L;
        }
        Long value = row.get(key);
        return value == null ? 0L : value;
    }

    private static long count(Map<String, Object> row, String key)
    {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String generatePassword()
    {
        while (true)
        {
            StringBuilder sb = new StringBuilder(PASSWORD_GENERATED_LEN);
            for (int i = 0; i < PASSWORD_GENERATED_LEN; i++)
            {
                sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
            }
            String password = sb.toString();
            boolean letter = false;
            boolean digit = false;
            for (int i = 0; i < password.length(); i++)
            {
                char c = password.charAt(i);
                letter |= Character.isLetter(c);
                digit |= Character.isDigit(c);
            }
            if (letter && digit)
            {
                return password;
            }
        }
    }

    /** 与 AppAuthServiceImpl 同口径：8~64 位且必须同时含字母与数字 */
    private static void checkPasswordStrength(String password)
    {
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX)
        {
            throw AppException.badRequest("密码长度必须是 " + PASSWORD_MIN + "~" + PASSWORD_MAX + " 位");
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++)
        {
            char c = password.charAt(i);
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit)
        {
            throw AppException.badRequest("密码必须同时包含字母和数字");
        }
    }

    private static String emptyToNull(String value)
    {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String format(LocalDateTime value)
    {
        return value == null ? null : value.format(DATE_TIME);
    }

    private static String text(Object value)
    {
        if (value == null)
        {
            return null;
        }
        return String.valueOf(value);
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
