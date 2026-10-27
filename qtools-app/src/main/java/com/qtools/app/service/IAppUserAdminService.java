package com.qtools.app.service;

import java.util.List;
import java.util.Map;
import com.qtools.common.core.page.TableDataInfo;

/**
 * 用户端账号 用户管理（管理端，P5.79 审核 → P5.81 完整用户管理）
 *
 * <p>契约：分页列表 / 统计 / 详情 / 通过 / 拒绝 / 通用改状态 / 批量通过 /
 * 改资料 / 重置密码 / 封禁 / 解封 / 真删除 / 批量封禁解封。</p>
 *
 * <p>幂等友好：审核与封禁在「目标与现状一致」时不算失败，返回当前行 + {@code changed:false}；
 * 改资料在「值与现状相同」时同样 {@code changed:false} 且不写库。</p>
 *
 * <p>封禁是独立维度（{@code banned}），不是审核状态：解封后账号回到原审核态，信息不丢。</p>
 *
 * @author qtools
 */
public interface IAppUserAdminService
{
    /** 分页列表：status ∈ pending / approved / rejected / banned（空=全部，approved 不含已封禁），keyword 匹配 username / nickname */
    TableDataInfo list(String status, String keyword, Integer page, Integer size);

    /** 顶部统计：total / pending / approved(正常，不含已封禁) / rejected / banned / newToday / activeRecently */
    Map<String, Object> stats();

    /** 单个账号详情（含审核记录、封禁字段、登录身份、收藏数） */
    Map<String, Object> detail(Long id);

    /** 审核：status ∈ pending / approved / rejected；note 仅 rejected 有意义（approved 时清空） */
    Map<String, Object> review(Long id, String status, String note, Long operatorId);

    /** 批量通过（多选行） */
    Map<String, Object> batchApprove(List<Long> ids, Long operatorId);

    /** 改资料：只改 body 里出现过的字段（nickname / avatar / reviewNote|note|remark） */
    Map<String, Object> updateProfile(Long id, Map<String, Object> body, Long operatorId);

    /** 重置密码：password 为空则生成随机密码并在响应里一次性返回 */
    Map<String, Object> resetPassword(Long id, String password, Long operatorId);

    /** 封禁：banned=1 + banned_reason + banned_at，并撤销该账号全部会话 */
    Map<String, Object> ban(Long id, String reason, Long operatorId);

    /** 解封：banned=0，原因与时间清空 */
    Map<String, Object> unban(Long id, Long operatorId);

    /** 真删除：级联清 token / identity / 媒体归属绑定，返回被删除的用户名 */
    Map<String, Object> delete(Long id, String confirm, Long operatorId);

    /** 批量封禁 */
    Map<String, Object> batchBan(List<Long> ids, String reason, Long operatorId);

    /** 批量解封 */
    Map<String, Object> batchUnban(List<Long> ids, Long operatorId);
}
