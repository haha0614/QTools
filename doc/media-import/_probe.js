
const fs=require('fs'), path=require('path');
const root='E:/111/视频', thumbs='E:/111/project/QTools/data/media/thumbs';
const rows=fs.readFileSync('E:/111/project/QTools/QTools/.audit-tmp/photos.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
let stats={photos:0,pExist:0,pvExist:0,pvBig:0,staleP:0,stalePv:0,missingP:0,missingPv:0};
const missingP=[],missingPv=[],staleP=[],stalePv=[];
for(const [album,seq,rel,isBig,hash] of rows){
  stats.photos++;
  const src=path.resolve(root, rel.replace(/[\\/]+/g,path.sep));
  for(const [sub,arr,missArr,staleArr] of [['p',[],missingP,staleP],['pv',[],missingPv,stalePv]]){
    if(sub==='pv'&&isBig!=='1') continue;
    const out=path.join(thumbs,sub,hash+'.jpg');
    let os; try{os=fs.statSync(out);}catch(e){ missArr.push(rel); stats[sub==='p'?'missingP':'missingPv']++; continue; }
    if(os.size<=0){ missArr.push(rel+' (empty)'); continue; }
    if(sub==='p') stats.pExist++; else stats.pvExist++;
    let ss; try{ss=fs.statSync(src);}catch(e){ continue; }
    if(os.mtimeMs < ss.mtimeMs){ staleArr.push([rel, new Date(os.mtimeMs).toISOString(), new Date(ss.mtimeMs).toISOString()]); stats[sub==='p'?'staleP':'stalePv']++; }
  }
}
console.log(JSON.stringify({pExist:stats.pExist,pvExist:stats.pvExist,missingP:stats.missingP,missingPv:stats.missingPv,staleP:stats.staleP,stalePv:stats.stalePv}));
console.log('sample missingP', missingP.slice(0,3));
console.log('sample missingPv', missingPv.slice(0,3));
console.log('sample stalePv', stalePv.slice(0,3));