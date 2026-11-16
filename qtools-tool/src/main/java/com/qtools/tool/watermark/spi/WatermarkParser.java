package com.qtools.tool.watermark.spi;

import com.qtools.tool.watermark.domain.vo.WatermarkParseVO;

/**
 * 平台解析器 SPI
 *
 * <p>加一个平台 = 新写一个实现类 + 打上 @Component，注册表自动收集，不改核心代码。</p>
 *
 * @author qtools
 */
public interface WatermarkParser
{
    /**
     * 是否能处理这个链接
     *
     * @param url 用户粘贴的内容
     * @return true 表示由本解析器处理
     */
    boolean supports(String url);

    /**
     * 解析出无水印地址
     *
     * @param url 用户粘贴的内容
     * @return 解析结果
     */
    WatermarkParseVO parse(String url);

    /**
     * 平台名称，用于报错文案与前端展示
     *
     * @return 平台名
     */
    String platformName();
}
