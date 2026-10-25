package com.qtools.media.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 自动扫描（文件监听）配置数据层：只读写 {@code sys_config} 里的三行
 *
 * <p>键名固定 {@code media.watchEnabled} / {@code media.watchDebounceSeconds} /
 * {@code media.watchQuietMinutes}（语义见 {@code doc/media_watch_config.sql} 与
 * {@link com.qtools.media.service.IMediaWatchConfigService}）。</p>
 *
 * <p>与 {@link MediaEditPolicyMapper} 同风格、同理由：qtools-media 只依赖 qtools-common，
 * 为读三个键拉进整个 qtools-system 不划算；本表的 {@code config_key} 又没有唯一索引，
 * 所以「插入」只能 {@code INSERT ... SELECT ... WHERE NOT EXISTS}。</p>
 *
 * <p>包名符合 framework 的 {@code @MapperScan("com.qtools.**.mapper")}，无需 {@code @Mapper}。</p>
 *
 * @author qtools
 */
public interface MediaWatchConfigMapper
{
    /** 参数值；键不存在（或 config_value 为 NULL）时返回 null */
    String selectValueByKey(@Param("configKey") String configKey);

    /** 键不存在才插入（config_name / remark 由调用方给，便于在若依「参数设置」里看懂） */
    int insertIfAbsent(@Param("configKey") String configKey, @Param("configName") String configName,
            @Param("configValue") String configValue, @Param("remark") String remark);

    /** 覆盖值（键不存在时影响 0 行，交给调用方先 {@link #insertIfAbsent}） */
    int updateValue(@Param("configKey") String configKey, @Param("configValue") String configValue);
}
