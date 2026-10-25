
const fs=require('fs'), path=require('path'), cp=require('child_process'), crypto=require('crypto');
const root='E:/111/视频', thumbs='E:/111/project/QTools/data/media/thumbs';
const ffmpeg='E:/111/project/QTools/data/media/runtime/ffmpeg.exe';
const rows=fs.readFileSync('E:/111/project/QTools/QTools/.audit-tmp/photos.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
const seen=new Set(); const picks=[];
for(const r of rows){ if(r[3]!=='1') continue; if(seen.has(r[4])) continue; if(picks.some(p=>p[0]===r[0])) continue; seen.add(r[4]); picks.push(r); if(picks.length>=3) break; }
const tmpdir='E:/111/project/QTools/QTools/.audit-tmp/verify'; fs.mkdirSync(tmpdir,{recursive:true});
const md5=(f)=>crypto.createHash('md5').update(fs.readFileSync(f)).digest('hex');
const dims=(f)=>{ const r=cp.spawnSync('E:/111/project/QTools/data/media/runtime/ffprobe.exe',['-v','error','-select_streams','v:0','-show_entries','stream=width,height','-of','csv=p=0',f],{encoding:'utf8'}); return (r.stdout||'').trim(); };
const out=[];
for(const [album,seq,rel,isBig,hash] of picks){
  const src=path.resolve(root, rel.replace(/[\\/]+/g,path.sep));
  const dst=path.join(tmpdir, hash+'.jpg');
  const a=['-y','-loglevel','error','-i',src,'-frames:v','1','-vf','scale=1600:-2','-q:v','4',dst];
  const r2=cp.spawnSync(ffmpeg,a,{encoding:'utf8'});
  out.push({album, seq, rel, hash, srcBytes: fs.statSync(src).size, pvDims: dims(path.join(thumbs,'pv',hash+'.jpg')), srcDims: dims(src), ffmpegStatus:r2.status, exist_md5: md5(path.join(thumbs,'pv',hash+'.jpg')), regen_md5: r2.status===0?md5(dst):null, same: r2.status===0 && md5(dst)===md5(path.join(thumbs,'pv',hash+'.jpg'))});
}
console.log(JSON.stringify(out,null,1));
