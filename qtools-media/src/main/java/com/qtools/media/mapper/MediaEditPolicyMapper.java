package com.qtools.media.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 媒体库「站端编辑策略」数据层：只读写 sys_config 里的一行
 *
 * <p>键名固定 {@code media.userEditing}，值域 {@code off / local / full}（语义见
 * {@code doc/media_edit_policy.sql} 与 {@link com.qtools.media.service.IMediaEditPolicyService}）。</p>
 *
 * <p>为什么不复用若依的 {@code ISysConfigService}：qtools-media 只依赖 qtools-common，
 * 拉进 qtools-system 会反向依赖整个系统模块；而这里只需要一个键的读写，
 * 直读 sys_config 比背一个跨模块依赖更小。</p>
 *
 * <p>包名符合 framework 的 {@code @MapperScan("com.qtools.**.mapper")}，无需 {@code @Mapper}。</p>
 *
 * @author qtools
 */
public interface MediaEditPolicyMapper
{
    /** 参数值；键不存在（或 config_value 为 NULL）时返回 null */
    String selectValueByKey(@Param("configKey") String configKey);

    /**
     * 键不存在才插入
     *
     * <p>该实例的 sys_config <b>没有</b> config_key 唯一索引，所以不能靠
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 做 upsert，只能用
     * {@code INSERT ... SELECT ... WHERE NOT EXISTS}。</p>
     */
    int insertIfAbsent(@Param("configKey") String configKey, @Param("configValue") String configValue);

    /** 覆盖值（键不存在时影响 0 行，交给调用方先 {@link #insertIfAbsent}） */
    int updateValue(@Param("configKey") String configKey, @Param("configValue") String configValue);
}
