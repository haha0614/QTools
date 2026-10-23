package com.qtools.tool.controller;

import java.util.List;

import com.qtools.tool.domain.ToolInfo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Log;
import com.qtools.common.core.controller.BaseController;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.common.enums.BusinessType;
import com.qtools.tool.service.IToolAdminService;
import com.qtools.common.utils.poi.ExcelUtil;
import com.qtools.common.core.page.TableDataInfo;

/**
 * 工具信息Controller
 * 
 * @author qing
 * @date 2026-10-21
 */
@RestController
@RequestMapping("/qtool/info")
public class ToolAdminController extends BaseController
{
    @Autowired
    private IToolAdminService toolAdminService;

    /**
     * 查询工具信息列表
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:list')")
    @GetMapping("/list")
    public TableDataInfo list(ToolInfo toolAdmin)
    {
        startPage();
        List<ToolInfo> list = toolAdminService.selectToolInfoList(toolAdmin);
        return getDataTable(list);
    }

    /**
     * 导出工具信息列表
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:export')")
    @Log(title = "工具信息", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ToolInfo toolAdmin)
    {
        List<ToolInfo> list = toolAdminService.selectToolInfoList(toolAdmin);
        ExcelUtil<ToolInfo> util = new ExcelUtil<ToolInfo>(ToolInfo.class);
        util.exportExcel(response, list, "工具信息数据");
    }

    /**
     * 获取工具信息详细信息
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(toolAdminService.selectToolInfoById(id));
    }

    /**
     * 新增工具信息
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:add')")
    @Log(title = "工具信息", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ToolInfo toolAdmin)
    {
        return toAjax(toolAdminService.insertToolInfo(toolAdmin));
    }

    /**
     * 修改工具信息
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:edit')")
    @Log(title = "工具信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ToolInfo toolAdmin)
    {
        return toAjax(toolAdminService.updateToolInfo(toolAdmin));
    }

    /**
     * 删除工具信息
     */
    @PreAuthorize("@ss.hasPermi('qtool:admin:remove')")
    @Log(title = "工具信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(toolAdminService.deleteToolInfoByIds(ids));
    }
}
