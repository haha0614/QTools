package com.qtools.tool.service;

import jakarta.servlet.http.HttpServletRequest;
import com.qtools.tool.domain.dto.ToolVisitAddDTO;

/**
 * 工具访问埋点 业务层
 *
 * @author qtools
 */
public interface IToolVisitService
{
    /**
     * 记录一次工具访问埋点
     *
     * <p>不校验 toolId 是否真实存在：前端存在兜底 id，宁可留下脏数据也不让接口报错。</p>
     *
     * @param dto 埋点入参（toolId 已由控制器校验非空）
     * @param request 当前请求（用于取客户端 IP 与 User-Agent）
     * @return 结果
     */
    public int addVisit(ToolVisitAddDTO dto, HttpServletRequest request);
}
