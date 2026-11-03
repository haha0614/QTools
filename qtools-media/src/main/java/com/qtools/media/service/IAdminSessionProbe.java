package com.qtools.media.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 管理端登录态探针（P6.08）
 *
 * <p>媒体模块自己不认识若依的后台 token（那是 {@code qtools-admin} 的 Security 体系），
 * 而 {@code /api/media/person/*}、{@code /api/media/album/*} 这些<b>站端接口也会被管理端界面复用</b>。
 * 管理端浏览器带着 {@code Admin-Token} cookie 来请求时，服务层如果只按 app token 判「访客」，
 * 就会出现「管理员在管理端看不到家长指导级人物的相册」这种荒谬结果。</p>
 *
 * <p>于是把「这次请求是不是管理员」做成一个可选探针：由 {@code qtools-admin} 提供实现
 * （它才有 {@code TokenService}），媒体模块只负责在判访客时先问一句。没有实现时（单测 / 媒体模块单独部署）
 * 一律按「不是管理员」处理，行为与改动前一致。</p>
 *
 * @author qtools
 */
public interface IAdminSessionProbe
{
    /**
     * 这次请求是否带着有效的管理端登录态
     *
     * @param request 当前请求（可为 null，调用方自行判空）
     * @return true = 管理员；false / 无法判断 = 不是
     */
    boolean isAdmin(HttpServletRequest request);
}
