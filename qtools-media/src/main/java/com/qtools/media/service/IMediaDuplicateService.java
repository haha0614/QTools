package com.qtools.media.service;

import java.util.Map;

/**
 * 重复文件合并 服务层（P4-A）：消费 media_video_alias / media_photo_alias
 *
 * <p><b>语义</b>：扫描器把「同一内容出现在第二条路径」的副本沉淀进别名表，主文件留在
 * {@code media_video} / {@code media_photo}。本服务把这份沉淀变成可操作的三件事：</p>
 * <ol>
 *   <li>列出「与主文件同内容、但只存在于别名表」的副本组；</li>
 *   <li>合并：可选把副本文件搬进回收站，然后删掉别名行（不留无文件的僵尸行）；</li>
 *   <li>保留：把某条副本提升为「主文件指向的那份」，其余不动磁盘。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：所有路径都过 {@link IMediaFileService#resolveMedia} /
 * {@link IMediaFileService#resolveWriteTarget}，越界 403；删文件<b>只走回收站</b>
 * （{@code _媒体站/trash/&lt;kind&gt;/&lt;rootId&gt;/}），没有任何真删路径。</p>
 *
 * @author qtools
 */
public interface IMediaDuplicateService
{
    /**
     * 重复文件分组分页
     *
     * @param kind {@code video} / {@code photo}
     * @return {@code {kind,page,size,total,rows:[{contentHash,primary:{id,relPath,size,rootId},
     *         aliases:[{id,relPath,size,rootId}],count,bytesWasted}]}}
     *         —— {@code count} = 该组别名行数，{@code bytesWasted} = 别名文件字节数之和（合并可回收的磁盘量）
     */
    Map<String, Object> list(String kind, int page, int size);

    /**
     * 合并一组：删掉别名行；{@code deleteFiles=true} 时先把别名文件搬进回收站
     *
     * <p>幂等：组里没有别名行时返回全 0 的同一结构，不报错。别名文件在磁盘上已不存在时
     * 不搬（计入 {@code missingFiles}），别名行照旧删除——那行指向的东西已经不在了。</p>
     *
     * @return {@code {kind,contentHash,removedRows,deletedFiles,missingFiles,kept,movedToTrash:[...]}}
     */
    Map<String, Object> merge(String kind, String contentHash, boolean deleteFiles);

    /**
     * 保留某条别名：把它提升为「主行指向的那份文件」
     *
     * <p>实现是<b>交换 rel_path</b>（主行 id 不变，别名行改指旧主路径），不重建行：
     * 外部可见的 id、标签、标记、评分全部原地保留，组内行数也不变。</p>
     *
     * @param id 别名行 id（{@code media_video_alias.id} / {@code media_photo_alias.id}）
     * @return {@code {kind,contentHash,kept:{id,relPath,size,rootId},previous:{relPath,size,rootId}}}
     */
    Map<String, Object> keep(String kind, String contentHash, String id);
}
