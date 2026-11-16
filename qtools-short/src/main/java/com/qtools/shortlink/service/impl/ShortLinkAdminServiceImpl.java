package com.qtools.shortlink.service.impl;

import com.qtools.common.utils.DateUtils;
import com.qtools.shortlink.domain.ShortLink;
import com.qtools.shortlink.mapper.ShortLinkMapper;
import com.qtools.shortlink.service.IShortLinkAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短链接Service业务层处理
 * 
 * @author qing
 * @date 2026-11-05
 */
@Service
public class ShortLinkAdminServiceImpl implements IShortLinkAdminService
{
    @Autowired
    private ShortLinkMapper shortLinkMapper;

    /**
     * 查询短链接
     * 
     * @param id 短链接主键
     * @return 短链接
     */
    @Override
    public ShortLink selectShortLinkById(Long id)
    {
        return shortLinkMapper.selectShortLinkById(id);
    }

    /**
     * 查询短链接列表
     * 
     * @param shortLink 短链接
     * @return 短链接
     */
    @Override
    public List<ShortLink> selectShortLinkList(ShortLink shortLink)
    {
        return shortLinkMapper.selectShortLinkList(shortLink);
    }

    /**
     * 新增短链接
     * 
     * @param shortLink 短链接
     * @return 结果
     */
    @Override
    public int insertShortLink(ShortLink shortLink)
    {
        shortLink.setCreateTime(DateUtils.getNowDate());
        return shortLinkMapper.insertShortLink(shortLink);
    }

    /**
     * 修改短链接
     * 
     * @param shortLink 短链接
     * @return 结果
     */
    @Override
    public int updateShortLink(ShortLink shortLink)
    {
        return shortLinkMapper.updateShortLink(shortLink);
    }

    /**
     * 批量删除短链接
     * 
     * @param ids 需要删除的短链接主键
     * @return 结果
     */
    @Override
    public int deleteShortLinkByIds(Long[] ids)
    {
        return shortLinkMapper.deleteShortLinkByIds(ids);
    }

    /**
     * 删除短链接信息
     * 
     * @param id 短链接主键
     * @return 结果
     */
    @Override
    public int deleteShortLinkById(Long id)
    {
        return shortLinkMapper.deleteShortLinkById(id);
    }
}
