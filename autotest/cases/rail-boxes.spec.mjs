import {test,expect} from '@playwright/test';
import {join} from 'node:path';
import {mkdirSync,writeFileSync} from 'node:fs';
import {ROOT,server} from '../lib/server.mjs';

test('independent underground station and tunnel volumes have separate beds',async()=>{
  test.setTimeout(1_800_000);
  const out=join(ROOT,'build','debug','rail-interfaces');
  mkdirSync(out,{recursive:true});
  const samples=[];
  // This town contains the verified pair of independent boxes in the seed-12345 world.
  // Keep alternatives for layouts that move the lines away from this crossing.
  for(const [x,z] of [[43719,-1481],[-19654,-19821],[-10232,-10272],[-32428,16380]]){
    const {contacts}=await server.stationContacts(x,z);
    for(const contact of contacts.slice(0,2)){
      const [a,b]=[contact.first,contact.second].sort((p,q)=>p[1]-q[1]);
      expect(b[1]-a[1]).toBeGreaterThanOrEqual(10);
      await server.generate(Math.floor(Math.min(a[0],b[0]))-3,Math.floor(Math.min(a[2],b[2]))-3,
        Math.ceil(Math.max(a[0],b[0]))+3,Math.ceil(Math.max(a[2],b[2]))+3);
      const rooms=[];
      for(const point of [a,b]){
        const cx=Math.floor(point[0]),cz=Math.floor(point[2]),level=Math.floor(point[1]);
        let room;
        for(const dx of [0,-1,1,-2,2])for(const dz of [0,-1,1]){
          if(room)break;
          const col=await server.columnProfile(cx+dx,cz+dz,level-2,level+16);
          const floor=col.blocks.filter(v=>v.top!=null&&Math.abs(v.y-level)<=2 && !['minecraft:stone','minecraft:dirt'].includes(v.id))
            .find(v=>!col.blocks.some(other=>other.y>v.y&&other.bottom!=null&&other.bottom<v.top+3));
          const roof=floor&&col.blocks.find(v=>v.y>floor.y&&v.bottom!=null);
          if(floor&&roof)room={x:cx+dx,z:cz+dz,floor,roof};
        }
        rooms.push(room);
      }
      samples.push({x,z,contact,rooms});
      writeFileSync(join(out,'stacked.json'),JSON.stringify(samples,null,2));
      expect(rooms[0],'lower box has a floor and a roof').toBeDefined();
      expect(rooms[1],'upper box has a floor and a roof').toBeDefined();
      expect(rooms[0].roof.top).toBeLessThan(rooms[1].floor.bottom);
    }
    if(samples.length)break;
  }
  expect(samples.length,'a fixed town with independent buried lines').toBeGreaterThan(0);
});
