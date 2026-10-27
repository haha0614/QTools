package com.qtools.media.service;

import java.util.Map;
import com.qtools.media.domain.MediaOwnerSession;

/**
 * 设备态 → 账号态的数据接管（P5.74）
 *
 * <p>「用户相关的配置应当跟随账号保存」这件事分两步走：先由
 * {@link IMediaOwnerService#resolve} 把登录态解析成账号归属键（之后的读写天然跟随账号），
 * 再在<b>第一次登录</b>时把本机（设备态）已有的数据并进账号——否则老用户的播放列表 / 收藏
 * 会在登录那一刻「消失」（数据还在设备态，只是账号态看不见）。</p>
 *
 * <p>合并规则（并集语义，幂等）：</p>
 * <ul>
 *   <li>播放列表：账号下已有同名 → 条目去重保序追加；没有同名 → 整条改归属（连同条目）；</li>
 *   <li>收藏 / 最近浏览：账号里没有的补进去，判重口径与单条写入一致（content_hash 优先、老字段回退）；</li>
 *   <li>保存的筛选：名字不冲突的整条改归属，冲突的留在设备态。</li>
 * </ul>
 *
 * <p>设备态数据一律<b>不删</b>：登出后回到设备态仍然原样可用；重复登录重复调用时，
 * 因为「账号里已经有了」而新增 0 —— 这就是幂等的证明方式（不是靠时间戳或标记位）。</p>
 *
 * @author qtools
 */
public interface IMediaAccountSyncService
{
    /**
     * 把当前请求所属设备的数据并入当前登录账号
     *
     * @param session 已解析的所有者上下文；未登录（匿名设备态）时抛 400
     * @return 统计：{@code synced, deviceOwner, accountOwner, userId, playlistsMoved, playlistsMerged,
     *         playlistItemsAdded, favoritesAdded, recentsAdded, filtersMoved}
     */
    Map<String, Object> syncDeviceToAccount(MediaOwnerSession session);
}
