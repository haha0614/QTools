package com.qtools.tool.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.tool.service.IToolInfoService;

/**
 * 工具列表 对外公开接口
 *
 * <p>类上标注 {@link Anonymous}，framework 的 PermitAllUrlProperties 会自动把本控制器下的
 * 全部接口加入 Security 的 permitAll 白名单，无需改动 SecurityConfig。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/api/toolInfo")
public class ToolInfoController
{
    @Autowired
    private IToolInfoService toolInfoService;

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * 查询工具列表（仅返回 status = '0' 的正常记录，按 sort 升序）
     *
     * <p>响应结构：{"code":200,"msg":"操作成功","data":[{"id":..,"toolName":..,"toolDesc":..,
     * "toolRoute":..,"icon":..,"sort":..}]}</p>
     *
     * @return 工具列表
     */
    @GetMapping("/list")
    public AjaxResult list()
    {
        log.info("获取工具列表");
        return AjaxResult.success(toolInfoService.selectEnabledToolList());
    }
}
