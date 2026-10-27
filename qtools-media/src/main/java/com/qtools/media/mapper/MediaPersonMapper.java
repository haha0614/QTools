package com.qtools.media.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.qtools.media.domain.MediaPerson;

/**
 * 媒体人物 数据层（media_person + 视频/相册的人物关联）
 *
 * <p>P3-A 之前这里只有一个只读方法（索引 people[] 用）。现在人物是一等实体：
 * 字典 CRUD、{@code media_video_actor.person_id} 绑定、{@code media_album_person} 多对多
 * 都在本文件里，风格与 {@link MediaOrganizeMapper} 一致 —— 写语句全部幂等
 * （INSERT IGNORE / 覆盖式 UPDATE），由 {@code MediaPersonServiceImpl} 在同一事务里组合。</p>
 *
 * <p>「全量替换」只动 {@code person_id IS NOT NULL} 的行：扫描脚本写的 name-only 历史行
 * （{@code person_id IS NULL}）永远不会被管理端的人物绑定清掉，与 {@code media_video_tag.tag_id}
 * 的处理完全对称。</p>
 *
 * @author qtools
 */
public interface MediaPersonMapper
{
    // ------------------------------------------------------------------ 人物字典

    /** 查询全部未隐藏人物，按 sort/id 升序（索引 people[] 用；含 P3-A 新增列） */
    List<MediaPerson> selectPersonList();

    /** 按 id 查（含 videoCount / albumCount 只读回填） */
    MediaPerson selectPersonDetail(@Param("id") Long id);

    /** 按名称查（新建 / 改名的重名校验） */
    MediaPerson selectPersonByName(@Param("name") String name);

    /** 分页：q 对 name / alias 模糊匹配；含 videoCount / albumCount */
    List<MediaPerson> selectPersonPage(@Param("q") String q, @Param("offset") int offset, @Param("size") int size);

    long countPersonPage(@Param("q") String q);

    /** 站端人物清单（id / name / alias / useCount），按 useCount 降序 */
    List<MediaPerson> selectPersonBriefList();

    /** 新建人物；主键回填到入参对象（useGeneratedKeys） */
    int insertPerson(MediaPerson person);

    /** 改名（撞别的行由服务层先查重） */
    int updatePersonName(@Param("id") Long id, @Param("name") String name);

    /** 改别名；null = 清空 */
    int updatePersonAlias(@Param("id") Long id, @Param("alias") String alias);

    /** 改备注；null = 清空 */
    int updatePersonNote(@Param("id") Long id, @Param("note") String note);

    /** 改性别；null = 清空 */
    int updatePersonGender(@Param("id") Long id, @Param("gender") String gender);

    /** 改生日；null = 清空 */
    int updatePersonBirthday(@Param("id") Long id, @Param("birthday") Date birthday);

    /** 改头像；null = 清空 */
    int updatePersonAvatar(@Param("id") Long id, @Param("avatarPath") String avatarPath);

    /** 改手动头像来源（P5.48）：两列一起写；null + null = 清除（回退三级自动） */
    int updatePersonAvatarRef(@Param("id") Long id, @Param("avatarKind") String avatarKind,
            @Param("avatarRef") String avatarRef);

    /**
     * 写人物卡背景调整三列（P5.89）：一次 UPDATE 写完，null = 该列为空
     *
     * <p>三列全为 null 时等价于「重置为自动几何」，服务层会直接走 {@link #clearPersonBg}。</p>
     */
    int updatePersonBg(@Param("id") Long id, @Param("scale") java.math.BigDecimal scale,
            @Param("dx") java.math.BigDecimal dx, @Param("dy") java.math.BigDecimal dy);

    /** 重置人物卡背景调整（P5.89）：三列写回 NULL */
    int clearPersonBg(@Param("id") Long id);

    /** 校验「该图片在该人物名下」：（albumId, seq）存在且相册属于这个人物（P5.48） */
    long countPersonPhotoOwned(@Param("personId") Long personId, @Param("albumId") String albumId,
            @Param("seq") int seq);

    /**
     * 覆盖式更新 P5-A 的 14 个资料字段（null = 清空该列）
     *
     * <p>一次 UPDATE 覆盖全部资料列而不是 14 个单列 UPDATE：服务层已经把「未出现的字段」
     * 合并成库里的现值，这里只需要照写，SQL 与字段一一对应、也不会出现只写一半的半成品行。</p>
     */
    int updatePersonProfile(@Param("id") Long id, @Param("person") MediaPerson person);

    int deletePerson(@Param("id") Long id);

    /** 按关联行实际数量重算 use_count（不做 +1/-1 漂移） */
    int recomputePersonUseCount(@Param("personIds") List<Long> personIds);

    /** 入参里实际存在的人物数（未知 personId 直接 400 用） */
    long countPersonsByIds(@Param("personIds") List<Long> personIds);

    /** 视频是否存在（绑人物前校验） */
    long countVideoById(@Param("videoId") String videoId);

    /** 相册是否存在（绑人物前校验） */
    long countAlbumById(@Param("albumId") String albumId);

    // ------------------------------------------------------------------ 视频 ↔ 人物

    /** 某视频绑定的人物（person_id IS NOT NULL 的行，按 role / name 排序） */
    List<Map<String, Object>> selectPersonActorsByVideoId(@Param("videoId") String videoId);

    /** 清掉某视频的全部人物绑定（不动 name-only 历史行） */
    int deleteVideoActorPersons(@Param("videoId") String videoId);

    /** 绑定一个人物到视频（name 从 media_person 冗余取；uk_actor_video_person 天然去重） */
    int insertVideoActorPerson(@Param("videoId") String videoId, @Param("personId") Long personId,
            @Param("role") String role);

    /** 某批视频当前绑定的人物 id（批量改完重算 use_count 用） */
    List<Long> selectPersonIdsByVideoIds(@Param("videoIds") List<String> videoIds);

    /** 某视频绑定的人物数（删人物前算引用；也用 videoCount） */
    long countVideoRefsByPerson(@Param("personId") Long personId);

    // ------------------------------------------------------------------ 相册 ↔ 人物

    /** 某相册绑定的人物（media_album_person ∪ media_album.person_id，含 id/name/alias） */
    List<Map<String, Object>> selectAlbumPersons(@Param("albumId") String albumId);

    /** 清掉某相册的全部人物关联（含把 media_album.person_id 置 NULL） */
    int deleteAlbumPersons(@Param("albumId") String albumId);

    int insertAlbumPerson(@Param("albumId") String albumId, @Param("personId") Long personId);

    /** 同步单选镜像列 media_album.person_id（= 多人物里的第一个，null = 未归类） */
    int updateAlbumPrimaryPerson(@Param("albumId") String albumId, @Param("personId") Long personId);

    List<Long> selectPersonIdsByAlbumIds(@Param("albumIds") List<String> albumIds);

    long countAlbumRefsByPerson(@Param("personId") Long personId);

    // ------------------------------------------------------------------ 站端人物页 / 索引

    /**
     * 某人物名下的视频分页（按 media_video_actor.person_id 命中，去重）
     *
     * <p>{@code hiddenRootIds}（P5.78）：未登录访客请求时传入 {@code guest_visible = 0} 的根 id，
     * 这些根下的视频连「条目数」都不出现（分页 total 用同一个条件算，不会出现
     * 「说 5 条但只列出 3 条」的错位）。null / 空 = 不过滤（登录用户与改动前逐字相同）。</p>
     */
    List<Map<String, Object>> selectPersonVideoPage(@Param("personId") Long personId, @Param("offset") int offset,
            @Param("size") int size, @Param("hiddenRootIds") List<Integer> hiddenRootIds);

    long countPersonVideos(@Param("personId") Long personId, @Param("hiddenRootIds") List<Integer> hiddenRootIds);

    /** 某人物名下的相册分页（media_album_person ∪ media_album.person_id）；hiddenRootIds 口径同上（按图片反推） */
    List<Map<String, Object>> selectPersonAlbumPage(@Param("personId") Long personId, @Param("offset") int offset,
            @Param("size") int size, @Param("hiddenRootIds") List<Integer> hiddenRootIds);

    long countPersonAlbums(@Param("personId") Long personId, @Param("hiddenRootIds") List<Integer> hiddenRootIds);

    /** 索引用：全部视频的人物绑定 [{videoId, personId, name, role}] */
    List<Map<String, Object>> selectVideoPersonList();

    /** 索引用：全部相册的人物 [{albumId, personId, name}]（含单选镜像列） */
    List<Map<String, Object>> selectAlbumPersonList();
}
