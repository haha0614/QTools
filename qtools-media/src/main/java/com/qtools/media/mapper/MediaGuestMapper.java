package com.qtools.media.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 未登录可见性判定（P5.78 / P5.99）的只读查询
 *
 * <p>只做一件事：把「这次请求指向的资源在哪个 {@code media_root} 行下」与「它属于哪一档内容分级」
 * 问出来。判定与拦截由 {@code IMediaGuestAccessService} 统一做，本接口不含任何业务规则。</p>
 *
 * <p>为什么单独一个 mapper 而不是散在各个业务 mapper 里：{@code media_root.guest_visible} 与
 * {@code media_rating_policy.guest_visible} 是<b>横切</b>于视频 / 相册 / 图片三类资源的东西，
 * 放进任何一个业务 mapper 都会让另外两类去 import 不相干的东西。</p>
 *
 * <p>P5.99 起每条「资源 → 位置」的点查都配一条「资源 → 分级」的点查，两个维度由服务层取<b>或</b>：
 * 未登录访客既看不到收紧库根里的内容，也看不到 {@code guest_visible=0} 那一档的内容。</p>
 *
 * @author qtools
 */
public interface MediaGuestMapper
{
    /** {@code guest_visible = 0} 的根 id 清单（空 = 当前没有任何根收紧，站端一切照旧） */
    List<Integer> selectGuestHiddenRootIds();

    /**
     * {@code guest_visible = 0} 的分级值清单（P5.99；空 = 三档都对未登录可见）
     *
     * <p>策略表由 {@code doc/media_content_rating.sql} 预置三行；表还没建时这里会抛异常，
     * 服务层按「全部可见」兜住（与根清单同一套失败方向）。</p>
     */
    List<String> selectGuestHiddenRatings();

    /** 视频所在根（不过滤 hidden：软删行也要按根判，否则「隐藏片」会漏出 404/200 的差异） */
    Integer selectRootIdOfVideo(@Param("id") String id);

    /** 视频的内容分级（P5.99）；视频不存在 / 列为空时返回 null（= 按 general 处理） */
    String selectRatingOfVideo(@Param("id") String id);

    /**
     * 相册所在根的去重清单（{@code media_album} 自己没有 {@code root_id}，按它的图片算）
     *
     * <p>返回多行是正常的：一个相册的图片理论上可以来自不同根。服务层按「有一张在隐藏根里
     * 就整册对未登录访客隐藏」处理（保守方向：宁可多藏，不漏）。</p>
     */
    List<Integer> selectRootIdsOfAlbum(@Param("albumId") String albumId);

    /** 相册自己的内容分级（P5.99）：整册一个值，册里的图片继承它 */
    String selectRatingOfAlbum(@Param("albumId") String albumId);

    /**
     * 按内容哈希找图片副本：{@code [{rootId, rating}]}（同一份内容可能存在于多个根 → 多行）
     *
     * <p>P5.99 把评级一起取回来，服务层用同一套「所有副本都不可见才判不可见」的口径
     * （多根 / 多册同一份文件是正常的）。</p>
     */
    List<Map<String, Object>> selectCopiesOfPhotoByHash(@Param("hash") String hash);

    /** 按「册内序号」找图片所在根（老 URL {@code /thumb/p/<albumId>/<seq>} 用） */
    Integer selectRootIdOfPhotoBySeq(@Param("albumId") String albumId, @Param("seq") Integer seq);

    /**
     * 人物头像来源 + 备用路径（P5.90 / P5.99）
     *
     * <p>只回三列：{@code avatar_kind} / {@code avatar_ref} / {@code avatar_path}。刻意不查
     * {@code selectPersonDetail} —— 那条 SQL 带 4 个子查询，只为判一个 404 太重。</p>
     *
     * @return {@code {kind, ref, avatarPath}}；人物不存在返回 null
     */
    Map<String, Object> selectAvatarOfPerson(@Param("id") Long id);

    /** 按相对路径找视频的分级（{@code /media/file|stream|sub?p=} 用；{@code media_video.rel_path} 有唯一索引） */
    String selectRatingOfVideoByRelPath(@Param("rel") String rel);

    /** 按相对路径找图片所属相册的分级（同上；{@code media_photo.rel_path} 的前缀索引由本次迁移补上） */
    String selectRatingOfAlbumByPhotoRelPath(@Param("rel") String rel);
}
