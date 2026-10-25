package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 媒体库重复文件合并（P4-A）数据层：读别名表 + 改主行路径
 *
 * <p>两张别名表结构同构，因此全部语句按 {@code kind}（{@code video} / {@code photo}，
 * 由服务层白名单校验后传入）在 XML 里 {@code <choose>} 选表，SQL 本体只有一份。</p>
 *
 * <p>写语句只用 {@code update} / {@code delete}，不写 {@code insert}：
 * 「提升别名为主行」通过<b>交换 rel_path</b> 完成，不重建行（外部可见 id 不变）。</p>
 *
 * @author qtools
 */
public interface MediaDuplicateMapper
{
    /**
     * 别名分组分页：{@code [{contentHash, aliasCount, aliasBytes}]}
     *
     * <p>只返回「主表里确实有该 hash 主行」的分组——孤立别名（主行已被删）不在列表里，
     * 因此列表上看到的每一组都一定能合并。</p>
     */
    List<Map<String, Object>> selectAliasGroups(@Param("kind") String kind, @Param("limit") int limit,
            @Param("offset") int offset);

    /** 分组总数（与 {@link #selectAliasGroups} 同一个 where，分页 total 用它） */
    int countAliasGroups(@Param("kind") String kind);

    /** 某 hash 的全部别名行（id 升序） */
    List<Map<String, Object>> selectAliasesByHash(@Param("kind") String kind,
            @Param("contentHash") String contentHash);

    /** 单个别名行；不存在返回 null */
    Map<String, Object> selectAliasById(@Param("kind") String kind, @Param("id") String id);

    /**
     * 该 hash 的主行：{@code {id, relPath, size, rootId, contentHash}}
     *
     * <p>video 侧唯一键保证只有一行；photo 侧同 hash 可能多行，取「未隐藏优先、id 最小」。</p>
     */
    Map<String, Object> selectPrimaryByHash(@Param("kind") String kind, @Param("contentHash") String contentHash);

    /** 主表里 rel_path 等于该值的行数（keep 前检查唯一键冲突用） */
    int countMainRowsByRelPath(@Param("kind") String kind, @Param("relPath") String relPath);

    /**
     * 改主行指向的文件：rel_path / root_id / size（video 另带 folder 冗余列）
     *
     * <p>只换「指哪个文件」，不动 id / 标题 / 标签 / 标记 —— 内容相同，用户数据全部保留。</p>
     */
    int updatePrimaryPath(@Param("kind") String kind, @Param("id") String id, @Param("relPath") String relPath,
            @Param("rootId") Integer rootId, @Param("size") long size, @Param("folder") String folder);

    /** 改别名行指向的文件（keep 把旧主路径写回别名行，组内行数不变） */
    int updateAliasPath(@Param("kind") String kind, @Param("id") String id, @Param("relPath") String relPath,
            @Param("rootId") Integer rootId, @Param("size") long size);

    int deleteAliasById(@Param("kind") String kind, @Param("id") String id);

    /** merge 收尾：一次删掉该 hash 的全部别名行（天然幂等：第二次影响 0 行） */
    int deleteAliasesByHash(@Param("kind") String kind, @Param("contentHash") String contentHash);
}
