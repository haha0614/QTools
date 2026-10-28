package com.qtools.media.support;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.qtools.media.domain.MediaRoot;
import com.qtools.media.mapper.MediaRootMapper;

/**
 * 启动自检（P5.95）：唯一内容根被关掉「未登录可见」时打一条 WARN
 *
 * <p>背景：{@code media_root} 里 {@code kind='root'} 的根一旦 {@code guest_visible=0}，未登录访客拿到的
 * 索引就是空的 —— 站点「看起来空了」，缩略图 / 封面 / 人物页背景还全部 404。这个事故实测发生过两次
 * （测试代理改完 flag 忘记恢复），而 {@code WARN} 日志是唯一能在部署当天就被人看见的信号。</p>
 *
 * <p>判定口径与产品定义严格一致：<b>内容根（{@code kind='root'}）数量为 1 且它的 {@code guest_visible=0}</b>。</p>
 *   - 有多个内容根时不报：还有别的根可见时访客看得到东西（是否报由管理端页面按「全部不可见」的规则提示）；
 *   - {@code guest_visible} 为 {@code null}（历史行 / 未迁移库）按「可见」处理，与
 *     {@code MediaMaintenanceServiceImpl.guestVisibleOf} 同口径，不误报。</p>
 *
 * <p><b>只记日志，绝不改数据</b>：自检必须对运行期零影响，因此这里只有一条 {@code select}；
 * 任何异常（库还没起来、表 / 列不存在、mapper 拿不到）都被 {@code catch} 成 {@code debug}，
 * 不会打断 {@link ApplicationReadyEvent} 的其它监听者，更不会影响应用启动。</p>
 *
 * @author qtools
 */
@Component
public class MediaGuestVisibleStartupCheck
{
    private static final Logger log = LoggerFactory.getLogger(MediaGuestVisibleStartupCheck.class);

    /** 内容根的类型值（与 scan-media.js 的 {@code --root-id} 缺省口径、管理端的「内容根」一致） */
    private static final String KIND_CONTENT_ROOT = "root";

    private final MediaRootMapper mediaRootMapper;

    public MediaGuestVisibleStartupCheck(MediaRootMapper mediaRootMapper)
    {
        this.mediaRootMapper = mediaRootMapper;
    }

    /**
     * 应用启动完成（容器就绪、所有 Bean 可用）后检查一次
     *
     * <p>挂在 {@link ApplicationReadyEvent} 而不是构造器 / {@code @PostConstruct}：那时数据源与 mapper
     * 可能还没完全就绪，而且启动日志里「Tomcat started」之后的那一行才有人看。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady()
    {
        checkOnce();
    }

    /**
     * 实际检查（包可见，便于将来接单测 / 手动触发）
     *
     * <p>任何异常都只打 debug：自检失败是「少一条提示」，绝不能变成启动失败。</p>
     */
    void checkOnce()
    {
        try
        {
            List<MediaRoot> roots = mediaRootMapper.selectRootList();
            if (roots == null || roots.isEmpty())
            {
                log.debug("未登录可见自检：media_root 为空，跳过");
                return;
            }
            List<MediaRoot> contentRoots = new ArrayList<>(2);
            for (MediaRoot root : roots)
            {
                if (root != null && KIND_CONTENT_ROOT.equalsIgnoreCase(root.getKind()))
                {
                    contentRoots.add(root);
                }
            }
            if (contentRoots.size() != 1)
            {
                log.debug("未登录可见自检：内容根 {} 个（仅唯一内容根时检查 guest_visible），跳过", contentRoots.size());
                return;
            }
            MediaRoot only = contentRoots.get(0);
            // null = 未迁移 / 历史行 → 按「未登录可见」处理，与 guestVisibleOf 同口径
            if (!Boolean.FALSE.equals(only.getGuestVisible()))
            {
                log.debug("未登录可见自检：唯一内容根 {}(id={}) guest_visible=1，正常", only.getName(), only.getId());
                return;
            }
            log.warn("检测到唯一内容根 {}(id={}) 的 guest_visible=0，未登录访客将看到空站点", only.getName(), only.getId());
        }
        catch (Exception e)
        {
            // 唯一允许的失败方向：安静跳过（库连不上 / 表列缺失 / mapper 未就绪都不该影响启动）
            log.debug("未登录可见启动自检跳过：{}", e.toString());
        }
    }
}
