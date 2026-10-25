package com.qtools.media.service;

import java.util.List;
import java.util.Map;

/**
 * 媒体站播放列表 服务层（P5-B）
 *
 * <p>数据按 {@code owner_key} 隔离：读 = 自己的 + 全局的（{@code owner_key IS NULL}），写只碰自己的行。
 * 站端所有写入口在进业务前由控制器调 {@code IMediaEditPolicyService#requireWritable()}，
 * 本层不再重复判策略（策略是 HTTP 语义，不是数据语义）。</p>
 *
 * <p>顺序约定：{@code seq} 从 0 起、同一列表内连续。全量替换与单条删除都由本层维持这个不变量
 * （删除后把后面的条目前移），调用方不需要关心 seq 的连续性。</p>
 *
 * @author qtools
 */
public interface IMediaPlaylistService
{
    /** 当前 owner 可见的列表：{@code [{id,name,count}]}，新→旧 */
    List<Map<String, Object>> list(String ownerKey);

    /** 新建；同名同 owner 复用（返回既有行，{@code created=false}，不是 409） */
    Map<String, Object> create(String ownerKey, String name);

    /** 详情：{@code {id,name,count,items:[{videoId,title,dur,seq}],videos:[同 items]}}；不存在 / 不属于自己 404 */
    Map<String, Object> detail(String ownerKey, Long id);

    /** 改名（{@code name} 非空时）与 / 或全量替换条目（{@code videoIds} 非 null 时，保序、去重） */
    Map<String, Object> update(String ownerKey, Long id, String name, List<String> videoIds);

    /** 追加一条：已在列表里就 no-op（{@code added=false}），否则挂到末尾 */
    Map<String, Object> addItem(String ownerKey, Long id, String videoId);

    /**
     * 删除一条并把后面的条目前移
     *
     * <p><b>幂等</b>：该视频本来不在列表里时返回 {@code removed:0}（200），不抛 404——
     * 「结果已经是你要的样子」不该让前端的整集合 PUT 兜底逻辑误判成失败。列表不存在 / 不是自己的仍然 404。</p>
     */
    Map<String, Object> removeItem(String ownerKey, Long id, String videoId);

    /** 删除整个列表（连同条目）；不存在 / 不属于自己 404 */
    Map<String, Object> delete(String ownerKey, Long id);

    /** 管理端全量清单（含归属与时间）：{@code [{id,name,count,ownerKey,global,createTime,updateTime}]} */
    List<Map<String, Object>> adminList();

    /** 管理端删除（不看归属，连条目一起删）；不存在 404 */
    Map<String, Object> adminDelete(Long id);
}
