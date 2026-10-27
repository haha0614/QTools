package com.qtools.app.service;

import java.util.List;
import java.util.Map;
import com.qtools.common.core.page.TableDataInfo;

/**
 * 用户端账号 审核管理（管理端，P5.79）
 *
 * <p>契约：分页列表 / 状态统计 / 详情 / 通过 / 拒绝 / 通用改状态 / 批量通过。
 * 所有写操作<b>幂等友好</b>：目标状态与当前状态一致时不算失败，返回当前行 + {@code changed:false}。</p>
 *
 * @author qtools
 */
public interface IAppUserAdminService
{
    /** 分页列表：status 空=全部，keyword 匹配 username / nickname */
    TableDataInfo list(String status, String keyword, Integer page, Integer size);

    /** 各状态数量 + 总数（管理端角标 / 顶部待审核提示） */
    Map<String, Object> stats();

    /** 单个账号详情（含审核备注、审核时间、审核人） */
    Map<String, Object> detail(Long id);

    /** 审核：status ∈ pending / approved / rejected；note 仅 rejected 有意义（approved 时清空） */
    Map<String, Object> review(Long id, String status, String note, Long operatorId);

    /** 批量通过（多选行） */
    Map<String, Object> batchApprove(List<Long> ids, Long operatorId);
}
