-- video_url 是「第 1 段」的缓存列（WebPlayController / selectVideoUrl 的 index=0 读它）。
-- 下载器历史上把「正在下载的第 N 段」写进过这一列，导致第 1 段播成第 N 段
-- （148950 第 1 段播成第 12 段）。写入口已在 resign-sidecar.mjs 修好，这里修存量。
-- 幂等：只在两者不一致时改；数组不合法或第 0 条不是 http 地址时不动。
use qtools;

update tool_web_post
set video_url = json_unquote(json_extract(video_urls, '$[0]'))
where video_urls is not null
  and json_valid(video_urls)
  and json_length(video_urls) > 0
  and json_unquote(json_extract(video_urls, '$[0]')) like 'http%'
  and (video_url is null or video_url <> json_unquote(json_extract(video_urls, '$[0]')));
