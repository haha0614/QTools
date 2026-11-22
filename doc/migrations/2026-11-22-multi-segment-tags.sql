use qtools;

-- 多段帖的整帖元数据只写在「裸文件名那一段」上（WebMediaImportService.applyTags 原来只解析一条 media_video 行），
-- 于是 <名>-2.mp4 … <名>-N.mp4 在管理端媒体库的「标签」列一直显示「无」。
-- 代码已改成遍历同帖全部段落（2026-11-22）；这里把历史上只落在第 1 段的标签复制到其余段。
-- 幂等：not exists 判断 (video_id, tag_id) 已存在就跳过。

insert into media_video_tag (video_id, facet, name, tag_id)
select sib.id, base_tag.facet, base_tag.name, base_tag.tag_id
from media_video sib
join media_video base on base.rel_path = regexp_replace(sib.rel_path, '-[0-9]+([.][A-Za-z0-9]+)$', '$1')
join media_video_tag base_tag on base_tag.video_id = base.id
where sib.rel_path regexp '-[0-9]+[.][A-Za-z0-9]+$'
  and not exists (select 1 from media_video_tag x where x.video_id = sib.id and x.tag_id = base_tag.tag_id);

-- 字典的 use_count 口径 = 关联行数（与 media_person 同一套规则），复制后按实际行数重算
update media_tag t set use_count = (select count(*) from media_video_tag vt where vt.tag_id = t.id);
