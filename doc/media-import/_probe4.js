
const fs=require('fs');
const j=JSON.parse(fs.readFileSync('E:/111/project/QTools/QTools/.audit-tmp/idx.json','utf8'));
const vWith=j.videos.filter(v=>v.persons&&v.persons.length);
const aWith=j.albums.filter(a=>a.persons&&a.persons.length);
console.log(JSON.stringify({videos:j.videos.length, videosWithPersons:vWith.length, albums:j.albums.length, albumsWithPersons:aWith.length, people:j.people.length, sampleVideos:vWith.slice(0,3).map(v=>({id:v.id,title:(v.title||'').slice(0,30),persons:v.persons})), sampleAlbum:aWith.slice(0,2).map(a=>({id:a.id,persons:a.persons}))},null,1));
