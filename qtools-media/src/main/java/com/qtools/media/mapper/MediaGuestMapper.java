package com.qtools.media.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 未登录可见性判定（P5.78）的只读查询
 *
 * <p>只做一件事：把「这次请求指向的资源在哪个 {@code media_root} 行下」问出来。
 * 判定与拦截由 {@code IMediaGuestAccessService} 统一做，本接口不含任何业务规则。</p>
 *
 * <p>为什么单独一个 mapper 而不是散在各个业务 mapper 里：{@code media_root} 的
 * {@code guest_visible} 是<b>横切</b>于视频 / 相册 / 图片三类资源的东西，
 * 放进任何一个业务 mapper 都会让另外两类去 import 不相干的东西。</p>
 *
 * @author qtools
 */
public interface MediaGuestMapper
{
    /** {@code guest_visible = 0} 的根 id 清单（空 = 当前没有任何根收紧，站端一切照旧） */
    List<Integer> selectGuestHiddenRootIds();

    /** 视频所在根（不过滤 hidden：软删行也要按根判，否则「隐藏片」会漏出 404/200 的差异） */
    Integer selectRootIdOfVideo(@Param("id") String id);

    /**
     * 相册所在根的去重清单（{@code media_album} 自己没有 {@code root_id}，按它的图片算）
     *
     * <p>返回多行是正常的：一个相册的图片理论上可以来自不同根。服务层按「有一张在隐藏根里
     * 就整册对未登录访客隐藏」处理（保守方向：宁可多藏，不漏）。</p>
     */
    List<Integer> selectRootIdsOfAlbum(@Param("albumId") String albumId);

    /** 按内容哈希找图片所在根（同一份内容可能存在于多个根 → 多行） */
    List<Integer> selectRootIdsOfPhotoByHash(@Param("hash") String hash);

    /** 按「册内序号」找图片所在根（老 URL {@code /thumb/p/<albumId>/<seq>} 用） */
    Integer selectRootIdOfPhotoBySeq(@Param("albumId") String albumId, @Param("seq") Integer seq);
}
