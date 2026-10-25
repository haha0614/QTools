
const fs=require('fs'), crypto=require('crypto');
const md5=(f)=>crypto.createHash('md5').update(fs.readFileSync(f)).digest('hex');
const T='E:/111/project/QTools/data/media/thumbs', A='E:/111/project/QTools/QTools/.audit-tmp/mv';
const pairs=[['pv/5b857b05ea3759c0.jpg','pv_5b857b05ea3759c0.jpg'],['p/5e546a13f67d6d81.jpg','p_5e546a13f67d6d81.jpg']];
console.log(JSON.stringify(pairs.map(([cur,bak])=>({file:cur, rebuilt:md5(T+'/'+cur), backup:md5(A+'/'+bak), identical: md5(T+'/'+cur)===md5(A+'/'+bak)})),null,1));
