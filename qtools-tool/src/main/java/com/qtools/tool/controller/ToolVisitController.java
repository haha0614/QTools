package com.qtools.tool.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.qtools.common.annotation.Anonymous;
import com.qtools.common.core.domain.AjaxResult;
import com.qtools.tool.domain.dto.ToolVisitAddDTO;
import com.qtools.tool.service.IToolVisitService;

/**
 * 工具访问埋点 对外公开接口
 *
 * <p>类上标注 {@link Anonymous}，framework 的 PermitAllUrlProperties 会自动把本控制器下的
 * 全部接口加入 Security 的 permitAll 白名单，无需改动 SecurityConfig。</p>
 *
 * @author qtools
 */
@Anonymous
@RestController
@RequestMapping("/api/toolVisit")
public class ToolVisitController
{
    @Autowired
    private IToolVisitService toolVisitService;

    /**
     * 记录一次工具访问
     *
     * <p>请求体：{"toolId": 1}；toolId 为空时由全局异常处理器返回校验提示。
     * 不校验 toolId 是否存在于 tool_info。</p>
     *
     * @param dto 埋点入参
     * @param request 当前请求（用于取客户端 IP 与 User-Agent）
     * @return 操作结果
     */
    @PostMapping("/add")
    public AjaxResult add(@Validated @RequestBody ToolVisitAddDTO dto, HttpServletRequest request)
    {
        toolVisitService.addVisit(dto, request);
        return AjaxResult.success();
    }
}
