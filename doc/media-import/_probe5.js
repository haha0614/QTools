
const fs=require('fs'), path=require('path');
const thumbs='E:/111/project/QTools/data/media/thumbs';
function count(sub){ const d=path.join(thumbs,sub); let files=0,dirs=0; for(const e of fs.readdirSync(d,{withFileTypes:true})){ if(e.isFile()) files++; else if(e.isDirectory()) dirs++; } return {sub,files,dirs}; }
console.log(JSON.stringify([count('p'),count('pv'),count('v'),count('s'),count('pv2')]));
const rows=fs.readFileSync('E:/111/project/QTools/QTools/.audit-tmp/photos.tsv','utf8').split('\n').filter(Boolean).map(l=>l.split('\t'));
const byHash=new Map(); for(const r of rows){ const k=r[4]; byHash.set(k,(byHash.get(k)||[]).concat([r])); }
const uniqBig=[...byHash.entries()].filter(([h,rs])=>rs.length===1&&rs[0][3]==='1');
const uniqAny=[...byHash.entries()].filter(([h,rs])=>rs.length===1);
console.log('uniqueBig',uniqBig.length,'uniqueAny',uniqAny.length);
const pickBig=uniqBig[0], pickAny=uniqAny[0];
console.log('PICK_BIG', JSON.stringify({hash:pickBig[0], row:pickBig[1][0]}));
console.log('PICK_ANY', JSON.stringify({hash:pickAny[0], row:pickAny[1][0]}));
