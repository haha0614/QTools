package com.qtools.tool.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.qtools.common.utils.DateUtils;
import com.qtools.common.utils.StringUtils;
import com.qtools.common.utils.ip.IpUtils;
import com.qtools.tool.domain.ToolVisit;
import com.qtools.tool.domain.dto.ToolVisitAddDTO;
import com.qtools.tool.mapper.ToolVisitMapper;
import com.qtools.tool.service.IToolVisitService;

/**
 * 工具访问埋点 业务层实现
 *
 * @author qtools
 */
@Service
public class ToolVisitServiceImpl implements IToolVisitService
{
    /** User-Agent 最大存储长度，与 tool_visit.user_agent 字段长度保持一致 */
    private static final int USER_AGENT_MAX_LENGTH = 512;

    @Autowired
    private ToolVisitMapper toolVisitMapper;

    /**
     * 记录一次工具访问埋点：tool_id / ip / user_agent / create_time
     *
     * @param dto 埋点入参（toolId 已由控制器校验非空）
     * @param request 当前请求（用于取客户端 IP 与 User-Agent）
     * @return 结果
     */
    @Override
    public int addVisit(ToolVisitAddDTO dto, HttpServletRequest request)
    {
        ToolVisit toolVisit = new ToolVisit();
        toolVisit.setToolId(dto.getToolId());
        toolVisit.setIp(IpUtils.getIpAddr(request));
        toolVisit.setUserAgent(StringUtils.substring(request.getHeader("User-Agent"), 0, USER_AGENT_MAX_LENGTH));
        toolVisit.setCreateTime(DateUtils.getNowDate());
        return toolVisitMapper.insertToolVisit(toolVisit);
    }
}
