package com.qtools.shortlink.mapper;

import com.qtools.shortlink.domain.ShortLink;

import java.util.List;

/**
 * 短链接Mapper接口
 * 
 * @author qing
 * @date 2026-11-05
 */
public interface ShortLinkMapper 
{
    /**
     * 查询短链接
     * 
     * @param id 短链接主键
     * @return 短链接
     */
    public ShortLink selectShortLinkById(Long id);

    /**
     * 查询短链接列表
     * 
     * @param shortLink 短链接
     * @return 短链接集合
     */
    public List<ShortLink> selectShortLinkList(ShortLink shortLink);

    /**
     * 新增短链接
     * 
     * @param shortLink 短链接
     * @return 结果
     */
    public int insertShortLink(ShortLink shortLink);

    /**
     * 修改短链接
     * 
     * @param shortLink 短链接
     * @return 结果
     */
    public int updateShortLink(ShortLink shortLink);

    /**
     * 删除短链接
     * 
     * @param id 短链接主键
     * @return 结果
     */
    public int deleteShortLinkById(Long id);

    /**
     * 批量删除短链接
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteShortLinkByIds(Long[] ids);

    public ShortLink getShortLinkByShortCode(String alias);

    ShortLink getShortLinkByUserIdAndUrlHash(Long userId, String urlHash);
}
