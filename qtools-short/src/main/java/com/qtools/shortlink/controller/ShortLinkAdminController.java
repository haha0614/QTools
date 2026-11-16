package com.qtools.shortlink.controller;

import com.qtools.common.annotation.Log;
import com.qtools.common.core.controller.BaseController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.core.page.TableDataInfo;
import com.qtools.common.enums.BusinessType;
import com.qtools.common.utils.poi.ExcelUtil;
import com.qtools.shortlink.domain.ShortLink;
import com.qtools.shortlink.service.IShortLinkAdminService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 短链接Controller
 * 
 * @author qing
 * @date 2026-11-05
 */
@RestController
@RequestMapping("/short/shortlink")
public class ShortLinkAdminController extends BaseController
{
    @Autowired
    private IShortLinkAdminService shortLinkService;

    /**
     * 查询短链接列表
     */
    @PreAuthorize("@ss.hasPermi('short:shortlink:list')")
    @GetMapping("/list")
    public TableDataInfo list(ShortLink shortLink)
    {
        startPage();
        List<ShortLink> list = shortLinkService.selectShortLinkList(shortLink);
        return getDataTable(list);
    }

    /**
     * 导出短链接列表
     */
    @PreAuthorize("@ss.hasPermi('short:shortlink:export')")
    @Log(title = "短链接", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ShortLink shortLink)
    {
        List<ShortLink> list = shortLinkService.selectShortLinkList(shortLink);
        ExcelUtil<ShortLink> util = new ExcelUtil<ShortLink>(ShortLink.class);
        util.exportExcel(response, list, "短链接数据");
    }

    /**
     * 获取短链接详细信息
     */
    @PreAuthorize("@ss.hasPermi('short:shortlink:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(shortLinkService.selectShortLinkById(id));
    }

    /**
     * 新增短链接
     */
    @PreAuthorize("@ss.hasPermi('short:shortlink:add')")
    @Log(title = "短链接", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ShortLink shortLink)
    {
        return toAjax(shortLinkService.insertShortLink(shortLink));
    }

    /**
     * 修改短链接
     */
    @PreAuthorize("@ss.hasPermi('short:shortlink:edit')")
    @Log(title = "短链接", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ShortLink shortLink)
    {
        return toAjax(shortLinkService.updateShortLink(shortLink));
    }

    /**
     * 删除短链接
     */
    @PreAuthorize("@ss.hasPermi('short:shortlink:remove')")
    @Log(title = "短链接", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(shortLinkService.deleteShortLinkByIds(ids));
    }
}
