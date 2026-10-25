
const fs=require('fs');
const rows=fs.readFileSync('E:/111/project/QTools/QTools/.audit-tmp/photos.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
const byHash=new Map(); for(const r of rows){ byHash.set(r[4],(byHash.get(r[4])||0)+1); }
const cand=rows.filter(r=>byHash.get(r[4])===1 && r[3]!=='1' && r[4]!=='5b857b05ea3759c0');
console.log(JSON.stringify(cand.slice(0,2)));
