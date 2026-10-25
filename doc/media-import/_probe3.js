
const fs=require('fs'), path=require('path'), cp=require('child_process');
const root='E:/111/视频', thumbs='E:/111/project/QTools/data/media/thumbs';
const ffprobe='E:/111/project/QTools/data/media/runtime/ffprobe.exe';
const rows=fs.readFileSync('E:/111/project/QTools/QTools/.audit-tmp/photos.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
const dims=(f)=>{ const r=cp.spawnSync(ffprobe,['-v','error','-select_streams','v:0','-show_entries','stream=width,height','-of','csv=p=0',f],{encoding:'utf8'}); const t=(r.stdout||'').trim().split('\n')[0]||''; const [w,h]=t.split(',').map(Number); return {w,h}; };
const seen=new Map();
for(const r of rows){ if(r[3]==='1'&&!seen.has(r[4])) seen.set(r[4], r); }
let ok=0, bad=[], pvWidths={};
for(const [hash,r] of seen){
  const src=path.resolve(root, r[2].replace(/[\\/]+/g,path.sep));
  const pv=path.join(thumbs,'pv',hash+'.jpg');
  const s=dims(src), o=dims(pv);
  pvWidths[o.w]=(pvWidths[o.w]||0)+1;
  const wantAspect = s.w/s.h, gotAspect = o.w/o.h;
  if(o.w===1600 && Math.abs(wantAspect-gotAspect) < 0.01) ok++; else bad.push({hash, rel:r[2], src:s, pv:o});
}
console.log(JSON.stringify({distinctBigHashes: seen.size, aspectMatch: ok, mismatch: bad.length, pvWidths, badSamples: bad.slice(0,5)}, null, 1));
// p 侧粗检：抽 5 个非 big 图片比 aspect
const nonBig=rows.filter(r=>r[3]!=='1').slice(0,5);
const pOut=[];
for(const r of nonBig){ const src=path.resolve(root, r[2].replace(/[\\/]+/g,path.sep)); const p=path.join(thumbs,'p',r[4]+'.jpg'); const s=dims(src),o=dims(p); pOut.push({hash:r[4], rel:r[2].slice(0,50), src:s, p:o, aspectOk: Math.abs(s.w/s.h-o.w/o.h)<0.01, widthOk:o.w===400}); }
console.log(JSON.stringify(pOut,null,1));
