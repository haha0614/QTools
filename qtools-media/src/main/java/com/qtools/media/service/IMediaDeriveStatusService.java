package com.qtools.media.service;

import java.util.Map;

/**
 * 派生资源状态查询 服务层（管理端列表的「是否已经生成 / 生成了哪些东西」）
 *
 * <p>只读：只看 {@code thumbs/{v,s,pv2}} 的产物与源文件 mtime，绝不生成、绝不删除任何文件。</p>
 *
 * @author qtools
 */
public interface IMediaDeriveStatusService
{
    /**
     * 分页查询派生资源状态
     *
     * @param ids  逗号分隔的 videoId（空 = 不按 id 筛）；≤200 个，形状不对 400
     * @param q    标题 / 相对路径模糊匹配（空 = 不过滤）
     * @param kind all（默认）| thumb | sprite | preview：后三者只回「该档缺失或过期」的行
     * @param page 页码（从 1 开始）
     * @param size 每页条数（默认 50，最大 200）
     * @return {rows:[{videoId,title,dur,thumb:{exists,stale,status,mtime,...},
     *         sprite:{...},preview:{exists,stale,status,frames,mtime,...},bytes}],
     *         total, page, size, kind, algoSince, algoSinceSource}
     *
     *         <p>{@code status} 三态：missing（产物不存在）/ oldAlgo（产物 mtime 早于算法上线时刻，
     *         取帧算法换过的那批旧预览片段）/ ok。{@code algoSince} / {@code algoSinceSource}
     *         说明判据取自标记文件 {@code <thumbs>/.derive-algo.json} 还是内置默认。</p>
     * @throws com.qtools.media.exception.MediaException 400 参数非法
     */
    Map<String, Object> deriveStatus(String ids, String q, String kind, int page, int size);
}
