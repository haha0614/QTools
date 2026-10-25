package com.qtools.media.service;

import java.util.List;
import java.util.Map;

/**
 * 保存的筛选条件（站端 + 管理端）
 *
 * <p>站端接口匿名（与收藏一致），但数据按 {@code owner_key} 隔离：读 = 自己的 + 全局的；
 * 写（保存 / 删除）受「站端编辑策略」{@code requireWritable} 约束，且只能删自己名下的条目。</p>
 *
 * <p>校验口径：{@code name} 去首尾空格后 1-64 字符；{@code params} 必须是合法 JSON 且
 * &lt; 4KB（UTF-8 字节）。非法一律 {@code 400}；同名同 owner 是<b>覆盖</b>，不是 409。</p>
 *
 * @author qtools
 */
public interface IMediaSavedFilterService
{
    /** GET /api/media/filters：{@code [{id,name,params}]}（params 已反序列化成 JSON 值） */
    List<Map<String, Object>> list(String ownerKey);

    /** POST /api/media/filters：新建或同名覆盖，回 {@code {id,name,params,created}} */
    Map<String, Object> save(String ownerKey, String name, String paramsJson);

    /** DELETE /api/media/filters/{id}：只能删自己名下，404 = 不存在或不属于自己 */
    Map<String, Object> delete(String ownerKey, Long id);

    /** GET /api/media/admin/filters：全量，含 ownerKey / createTime / updateTime */
    List<Map<String, Object>> adminList();

    /** DELETE /api/media/admin/filters/{id}：不看归属，404 = 不存在 */
    Map<String, Object> adminDelete(Long id);
}
