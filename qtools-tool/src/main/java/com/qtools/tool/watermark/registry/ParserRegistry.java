package com.qtools.tool.watermark.registry;

import java.util.List;

import org.springframework.stereotype.Component;

import com.qtools.common.exception.ServiceException;
import com.qtools.tool.watermark.spi.WatermarkParser;

/**
 * 解析器注册表：按链接匹配第一个支持它的平台解析器
 *
 * @author qtools
 */
@Component
public class ParserRegistry
{
    /** Spring 会把所有 WatermarkParser 实现注入进来 */
    private final List<WatermarkParser> parsers;

    public ParserRegistry(List<WatermarkParser> parsers)
    {
        this.parsers = parsers;
    }

    /**
     * 找到能处理该链接的解析器
     *
     * @param url 用户粘贴的内容
     * @return 解析器
     */
    public WatermarkParser find(String url)
    {
        for (WatermarkParser parser : parsers)
        {
            if (parser.supports(url))
            {
                return parser;
            }
        }
        StringBuilder supported = new StringBuilder();
        for (WatermarkParser parser : parsers)
        {
            if (supported.length() > 0)
            {
                supported.append('、');
            }
            supported.append(parser.platformName());
        }
        throw new ServiceException("暂不支持该平台，当前支持：" + supported);
    }
}
