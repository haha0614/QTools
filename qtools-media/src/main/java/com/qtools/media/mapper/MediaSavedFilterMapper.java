package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaSavedFilter;

/**
 * 保存的筛选条件 media_saved_filter 数据层（P2-A）
 *
 * <p>唯一键是 {@code (name, owner_key)}：同名同 owner 覆盖、不同 owner 可以各存一份同名。
 * {@code owner_key} 为 NULL 的全局条目靠 {@code is null} 匹配（MySQL 唯一索引不判 NULL 重复，
 * 全局条目只由管理端/种子写入，站端写路径永远带 owner_key）。</p>
 *
 * <p>写路径刻意<b>不用</b> {@code ON DUPLICATE KEY}：服务层先按 {@code (name, owner_key)} 查一次，
 * 命中就 {@code updateParamsById}、没命中才 {@code insert}——这样「新建 / 同名覆盖」两条路径的返回值
 * 都是确定的（{@code created} 标志、id），不必依赖 {@code last_insert_id} 的边角语义。</p>
 *
 * @author qtools
 */
public interface MediaSavedFilterMapper
{
    /** 当前 owner 可见的列表：自己的 + 全局的（owner_key is null），新→旧 */
    List<MediaSavedFilter> selectVisibleList(@Param("ownerKey") String ownerKey);

    /** 管理端全量清单（按 owner_key、name 升序；owner_key 为 null 的全局条目排最前） */
    List<MediaSavedFilter> selectAll();

    MediaSavedFilter selectById(@Param("id") Long id);

    /** 按 (name, owner_key) 精确匹配；ownerKey 为 null 时匹配全局条目 */
    MediaSavedFilter selectByNameAndOwner(@Param("name") String name, @Param("ownerKey") String ownerKey);

    /** 新增，自增 id 回填到 {@code filter.id} */
    int insert(MediaSavedFilter filter);

    /** 同名覆盖：只更新 params 与 update_time */
    int updateParamsById(@Param("id") Long id, @Param("params") String params);

    /** 站端删除：只能删自己名下的（ownerKey 为 null 时匹配全局条目） */
    int deleteByIdAndOwner(@Param("id") Long id, @Param("ownerKey") String ownerKey);

    /** 管理端删除：不看归属 */
    int deleteById(@Param("id") Long id);
}
