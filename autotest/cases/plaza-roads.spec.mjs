import {test,expect} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {ROOT,server} from '../lib/server.mjs';

test('the station driveway leaves the through lanes intact in the photographed block',async()=>{
  const plaza=await server.station(-10232,-10272);
  expect(plaza.roadsideLayout).toBe('PULL_OUT');
  expect(plaza.driveways,'a separate entry and exit at the near kerb').toBe(2);
  expect(plaza.bays.map(b=>b.use).sort()).toEqual(['BUS','DROP_OFF','TAXI']);
  const heading=plaza.heading*Math.PI/180, outward=plaza.outward*Math.PI/180;
  const world=(u,v)=>[plaza.x+u*Math.cos(heading)+v*Math.cos(outward),
    plaza.z+u*Math.sin(heading)+v*Math.sin(outward)];
  // Surveyed centrelines from the original plan. Follow each traffic lane across
  // the conflict, independently of the new plaza mask or any alternative BFS route.
  const corridors=[
    ['boulevard',world(9.1,71),world(14.3,127.2),[-8,-5,5,8]],
    ['arterial-west',world(-46.2,77.1),world(9.1,71),[-6,6]],
    ['cross-town',world(-46.2,77.1),world(65.2,65),[-1.5,1.5]]
  ];
  const checks=plaza.curbLanes.map((lane,i)=>({name:'receiving-lane-'+i,lane}));
  for(const [name,a,b,offsets] of corridors) {
    const length=Math.hypot(b[0]-a[0],b[1]-a[1]);
    const dx=(b[0]-a[0])/length,dz=(b[1]-a[1])/length;
    for(const off of offsets) {
      const lane=[];
      for(let t=0;t<=length;t+=0.5) {
        // The outer lanes merge before the narrow arm. Survey a vehicle's path
        // through the merge, including the continuing inner lane on either side.
        let lateral=off;
        if(name==='arterial-west') lateral=Math.sign(off)*(1.5+4.5*Math.min(1,(length-t)/48));
        if(name==='boulevard') {
          const fraction=Math.min(1,(t+7.5)/42,(73-t)/42);
          const narrow=Math.abs(off)===8?3.75:2;
          lateral=Math.sign(off)*(narrow+(Math.abs(off)-narrow)*fraction);
        }
        lane.push([a[0]+t*dx-lateral*dz,a[1]+t*dz+lateral*dx]);
      }
      checks.push({name:name+' '+off,lane});
    }
  }
  const points=checks.flatMap(c=>c.lane),xs=points.map(v=>Math.floor(v[0])),zs=points.map(v=>Math.floor(v[1]));
  expect((await server.generate(Math.min(...xs)-2,Math.min(...zs)-2,Math.max(...xs)+2,Math.max(...zs)+2)).missing).toBe(0);
  const columns=new Map(),failures=[],profiles=[];
  const roadAt=async(x,z)=>{
    const key=x+','+z;
    if(!columns.has(key)) {
      const {blocks}=await server.columnProfile(x,z,72,100);
      const floor=blocks.findLast(v=>/citybuilder:(asphalt|manhole|drain_grate)/.test(v.id)
        && v.top!=null && !blocks.some(o=>o.y>v.y&&o.bottom!=null&&o.bottom<v.top+4));
      columns.set(key,floor?.top??null);
    }
    return columns.get(key);
  };
  for(const {name,lane} of checks) {
    let last,lastKey;
    const profile=[];
    for(const [wx,wz] of lane) {
      const x=Math.floor(wx),z=Math.floor(wz),key=x+','+z;
      if(key===lastKey) continue;
      lastKey=key;
      const y=await roadAt(x,z);
      profile.push([x,y,z]);
      if(y===null || (last!=null && Math.abs(y-last)>0.5)) failures.push({name,x,z,y,last});
      last=y;
    }
    profiles.push({name,profile});
  }
  const dir=join(ROOT,'build','debug','station-scale','layout-8');
  mkdirSync(dir,{recursive:true});
  const laneColumns=columns.size;
  const widths=[];
  for(const [name,end,distances] of [
    ['boulevard',world(14.3,127.2),[12,16,20,24,28,32]],
    ['arterial-west',world(-46.2,77.1),[12,16,20,24,28,32,36]]
  ]) {
    const start=world(9.1,71),len=Math.hypot(end[0]-start[0],end[1]-start[1]);
    const dx=(end[0]-start[0])/len,dz=(end[1]-start[1])/len;
    const edges=[];
    for(const t of distances) {
      const pair=[];
      for(const side of [-1,1]) {
        let edge=2;
        for(let off=name==='boulevard'?3.5:2.5;off<=14;off+=0.5) {
          const x=Math.floor(start[0]+t*dx-side*off*dz),z=Math.floor(start[1]+t*dz+side*off*dx);
          if(await roadAt(x,z)===null) break;
          edge=off;
        }
        pair.push(edge);
      }
      edges.push(pair);
    }
    widths.push({name,distances,edges});
  }
  writeFileSync(join(dir,'through-lanes.json'),JSON.stringify({lanes:checks.length,columns:laneColumns,failures,profiles,widths},null,2));
  expect(failures.slice(0,4),'a retained traffic lane is cut or obstructed').toEqual([]);
  for(const {name,edges} of widths) for(let side=0;side<2;side++) {
    expect(edges.at(-1)[side]-edges[0][side],name+' must widen over multiple sections').toBeGreaterThanOrEqual(2);
    for(let i=1;i<edges.length;i++) expect(Math.abs(edges[i][side]-edges[i-1][side]),name+' kerb jump').toBeLessThanOrEqual(2);
  }
});
