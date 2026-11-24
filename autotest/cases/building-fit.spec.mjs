import { test,expect } from '@playwright/test';
import { mkdirSync,writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT,server } from '../lib/server.mjs';
const town=[-10232,-10272], out=join(ROOT,'build','debug','building-fit');

test('a refitted rail-side wedge has a room in the actual block footprint',async()=>{
  await server.planAround(...town);
  const report=await server.railLots(...town);
  expect(report.found,'a formerly rejected railway lot was fitted').toBe(true);
  mkdirSync(out,{recursive:true});
  for(const b of report.sites){
    const [fx,fz]=b.front;
    const r=Math.ceil(b.width/2+b.depth+4);
    await server.generate(Math.floor(fx)-r,Math.floor(fz)-r,Math.ceil(fx)+r,Math.ceil(fz)+r);
    const rooms=new Map(), doors=[];
    for(let x=Math.floor(fx)-r;x<=fx+r;x++)for(let z=Math.floor(fz)-r;z<=fz+r;z++){
      const dx=x+0.5-fx,dz=z+0.5-fz;
      const along=dx*b.along[0]+dz*b.along[1], depth=dx*b.inward[0]+dz*b.inward[1];
      if(Math.abs(along)>b.width/2 || depth<0 || depth>b.depth)continue;
      const p=await server.plot(x+0.5,z+0.5);
      if(!p.covered || p.buildingId!==b.id)continue;
      const sample=await server.columnProfile(x,z,b.base,b.base+Math.max(8,p.groundStorey+2));
      const floor=sample.blocks[0],ceiling=sample.blocks.find(block=>block.y>b.base&&block.bottom!=null);
      if(floor.top!=null && ceiling && ceiling.bottom-floor.top>=2)rooms.set(x+','+z,[x,z,ceiling.bottom-floor.top]);
      if(sample.blocks.some(row=>row.id.endsWith('_door')&&row.state.includes('half=lower')))doors.push([x,z]);
    }
    const patch=[...rooms.values()].find(([x,z])=>[[1,0],[0,1],[1,1]].every(([dx,dz])=>rooms.has((x+dx)+','+(z+dz))));
    b.clearColumns=rooms.size;b.room=patch;b.doors=doors;
    if(!patch) continue;
    for(let d=1;d<=Math.max(24,r)&&!b.street;d++) {
      const x=Math.floor(fx-d*b.inward[0]),z=Math.floor(fz-d*b.inward[1]);
      if(!(await server.plot(x+0.5,z+0.5)).onStreet) continue;
      const profile=await server.columnProfile(x,z,b.base-6,b.base+6);
      const paving=profile.blocks?.findLast(v=>v.top!=null && /asphalt|sidewalk|curb_edge/.test(v.id));
      if(paving) b.street={x,z,y:Math.round(paving.top)};
    }
    if(b.street) b.access=await server.walkRoute({mode:'lot',...b.street,
      endX:patch[0],endZ:patch[1],endY:b.base+1,margin:8});
  }
  writeFileSync(join(out,'wedges.json'),JSON.stringify(report,null,2));
  expect(report.sites.length).toBeGreaterThan(0);
  for(const b of report.sites) {
    expect(b.room,'usable interior of '+b.id).toBeDefined();
    expect(b.access?.found,'street to room of '+b.id).toBe(true);
  }
});

test('the civic entrance and a roadside facility meet their actual streets',async()=>{
  const list=(await server.facilities(...town)).placed;
  mkdirSync(out,{recursive:true});
  for(const kind of ['CITY_HALL','CLINIC']){
    const site=list.find(p=>p.kind===kind&&p.aligned&&p.doors?.length);
    expect(site,'a real aligned entrance for '+kind).toBeDefined();
    const path=list.find(p=>p.id===site.id&&p.path);
    expect(path).toBeDefined();
    await server.generate(Math.min(site.minX,path.minX)-8,Math.min(site.minZ,path.minZ)-8,
                          Math.max(site.maxX,path.maxX)+8,Math.max(site.maxZ,path.maxZ)+8);
    const starts=[];
    for(let x=path.minX-1;x<=path.maxX+1;x++)for(let z=path.minZ-1;z<=path.maxZ+1;z++){
      if(!(await server.plot(x+0.5,z+0.5)).onStreet)continue;
      const p=await server.columnProfile(x,z,site.ground-3,site.ground+3);
      const surface=p.blocks.findLast(b=>b.top!=null);
      if(surface && /asphalt|sidewalk|curb_edge/.test(surface.id))starts.push([x,Math.round(surface.top),z]);
    }
    expect(starts.length,'approach reaches the street for '+kind).toBeGreaterThan(0);
    const doors=site.doors.sort((a,b)=>Math.hypot(a[0]-starts[0][0],a[2]-starts[0][2])-Math.hypot(b[0]-starts[0][0],b[2]-starts[0][2]));
    const [endX,endY,endZ]=doors[0];
    const gate=await server.columnProfile(endX,endZ,endY,endY+1);
    expect(gate.blocks.some(b=>b.id.endsWith('_door')),'door actually placed').toBe(true);
    const route=await server.walkRoute({x:starts[0][0],y:starts[0][1],z:starts[0][2],endX,endY,endZ,margin:12});
    writeFileSync(join(out,kind+'.json'),JSON.stringify({site,path,start:starts[0],route},null,2));
    expect(route.found,'walk from street to '+kind+' door').toBe(true);
  }
});
