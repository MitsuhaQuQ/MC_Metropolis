import {test, expect} from '@playwright/test';
import {mkdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {ROOT, server} from '../lib/server.mjs';

const out = join(ROOT,'build','debug','station-scale','layout-'+(process.env.AUTOTEST_LAYOUT??'9'));

function overhead(blocks, floor) {
  const obstacle = blocks.find(b => b.y > floor.y && b.bottom != null);
  return obstacle ? obstacle.bottom-floor.top : Infinity;
}

async function turningSpace(row, other, level) {
  const cx=(row.x0+row.x1)/2, cz=(row.z0+row.z1)/2;
  const vx=cx-(other.x0+other.x1)/2, vz=cz-(other.z0+other.z1)/2;
  const dx=Math.abs(vx)>=Math.abs(vz)?Math.sign(vx):0, dz=dx===0?Math.sign(vz):0;
  const free=[];
  for(let across=-2;across<=2;across++) {
    let clear=true;
    for(let distance=1;distance<=2;distance++) {
      const x=Math.floor(cx)+dx*distance-dz*across, z=Math.floor(cz)+dz*distance+dx*across;
      const {blocks}=await server.columnProfile(x,z,level,level+5);
      const floor=blocks[0];
      clear=clear && floor.top===level+1 && !floor.id.endsWith('_stairs') && overhead(blocks,floor)>=2;
    }
    free.push(clear);
  }
  const pair=free.findIndex((v,i)=>i>0 && v && free[i-1]);
  if (pair<0) return null;
  const across=pair-2;
  return {x:Math.floor(cx)+2*dx-dz*across,z:Math.floor(cz)+2*dz+dx*across,y:level+1};
}

async function platformPassage(treads, level, sign) {
  const paving=new Set(['minecraft:smooth_stone','minecraft:polished_andesite','minecraft:white_concrete',
    'minecraft:yellow_concrete','minecraft:yellow_terracotta']);
  const widths=[];
  let previous;
  for(const row of treads) {
    const dx=Math.sign(row.x1-row.x0), dz=Math.sign(row.z1-row.z0);
    const x0=sign<0?row.x0:row.x1, z0=sign<0?row.z0:row.z1;
    let run=0,widest=0;
    const pairs=new Set();
    for(let d=1;d<=8;d++) {
      const x=x0+d*sign*dx, z=z0+d*sign*dz;
      const {blocks}=await server.columnProfile(x,z,level,level+5);
      const floor=blocks[0];
      if (!paving.has(floor.id)) break;
      run=floor.top===level+1 && overhead(blocks,floor)>=2 ? run+1 : 0;
      if (run>=2) {
        const across=dx?Math.min(x,x-sign*dx):Math.min(z,z-sign*dz);
        if(!previous || [across-1,across,across+1].some(pos=>previous.has(pos))) pairs.add(across);
      }
      widest=Math.max(widest,run);
    }
    widths.push({x:x0,z:z0,widest,connected:pairs.size>0});
    previous=pairs;
  }
  return widths;
}

for (const underground of [false,true]) for (const interchange of [false,true]) {
  const name = (underground ? 'underground' : 'elevated')+'-'+(interchange ? 'interchange' : 'ordinary');
  test('passenger clearance at '+name, async()=>{
    let space;
    const towns = underground
      ? interchange ? [[43719,-1481],[-19654,-19821]] : [[-19654,-19821],[43719,-1481]]
      : [[-10560,-8064],[43719,-1481]];
    for (const [x,z] of towns) {
      const sample = await server.passengerSpace(x,z,underground,interchange);
      if (sample.found && sample.space.stairs.length) { space=sample.space; break; }
    }
    expect(space,'a naturally generated station with passenger stairs').toBeDefined();
    const report = {space, stairs:[], hall:[]};
    mkdirSync(out,{recursive:true});
    try {
      for (const stair of space.stairs) {
        const [sx,sy,sz] = stair.step.map(Math.floor);
        const xs=stair.treads.flatMap(t=>[t.x0,t.x1]), zs=stair.treads.flatMap(t=>[t.z0,t.z1]);
        const generated=await server.generate(Math.min(...xs)-8,Math.min(...zs)-8,Math.max(...xs)+8,Math.max(...zs)+8);
        expect(generated.missing,'all chunks of the stair are loaded').toBe(0);
        let centre;
        for (const [dx,dz] of [[0,0],[-1,0],[1,0],[0,-1],[0,1]]) {
          const x=sx+dx, z=sz+dz;
          const {blocks}=await server.columnProfile(x,z,sy-2,sy+5);
          const step=blocks.find(b=>b.y===sy && b.id.endsWith('_stairs') && overhead(blocks,b)>=2);
          if (step) { centre={x,z,step}; break; }
        }
        expect(centre,'a clear tread at '+stair.step).toBeDefined();
        const alongX=/facing=(east|west)/.test(centre.step.state);
        let width=1;
        for (const sign of [-1,1]) for (let i=1;i<=stair.width+2;i++) {
          const x=centre.x+(alongX?0:sign*i), z=centre.z+(alongX?sign*i:0);
          const {blocks}=await server.columnProfile(x,z,sy,sy+4);
          const step=blocks[0];
          if (step.state!==centre.step.state || overhead(blocks,step)<2) break;
          width++;
        }
        const measurement={centre,width,bypass:[]};
        report.stairs.push(measurement);
        expect(width,'clear tread width').toBeGreaterThanOrEqual(Math.max(4,stair.width));
        measurement.rows=[];
        for(const row of stair.treads) {
          const dx=Math.sign(row.x1-row.x0), dz=Math.sign(row.z1-row.z0);
          const count=Math.max(Math.abs(row.x1-row.x0),Math.abs(row.z1-row.z0))+1;
          let run=0,clearWidth=0;
          for(let i=0;i<count;i++) {
            const x=row.x0+i*dx,z=row.z0+i*dz;
            const {blocks}=await server.columnProfile(x,z,row.y,row.y+4);
            const floor=blocks[0];
            const shape=row.landing ? floor.top===row.y+1 : floor.id.endsWith('_stairs');
            run=shape&&overhead(blocks,floor)>=2 ? run+1 : 0;
            clearWidth=Math.max(run,clearWidth);
          }
          measurement.rows.push({...row,clearWidth});
          expect(clearWidth,'clear width at '+[row.x0,row.y,row.z0]).toBeGreaterThanOrEqual(stair.width);
        }
        for (const [wx,y,wz] of stair.bypass) {
          const x=Math.floor(wx), z=Math.floor(wz);
          const {blocks}=await server.columnProfile(x,z,y-1,y+6);
          const floor=blocks.find(b=>Math.abs(b.y-y)<=1 && b.top!=null && !b.id.endsWith('_stairs')
            && !/barrier|fence|wall|bars|gravel|ballast/.test(b.id) && overhead(blocks,b)>=2);
          measurement.bypass.push({x,z,floor});
          expect(floor,'platform passage beside stair at '+[x,y,z]).toBeDefined();
        }
        measurement.platformPassages=[];
        for (const sign of [-1,1]) {
          const path=await platformPassage(stair.treads,stair.bypass[0][1],sign);
          measurement.platformPassages.push(path);
          const gap=path.find(p=>!p.connected);
          expect(gap,'two continuous walking columns alongside the flight').toBeUndefined();
        }
        const first=stair.treads[0], last=stair.treads.at(-1);
        measurement.lowerLanding=await turningSpace(first,last,first.y-1);
        measurement.upperLanding=await turningSpace(last,first,last.y);
        expect(measurement.lowerLanding,'turning space at the foot of the flight').not.toBeNull();
        expect(measurement.upperLanding,'turning space at the head of the flight').not.toBeNull();
      }
      const [hx,hy,hz]=space.hall.map(Math.floor);
      expect((await server.generate(hx-4,hz-4,hx+4,hz+4)).missing,'hall chunks loaded').toBe(0);
      const open=new Set();
      for(let dx=-3;dx<=3;dx++) for(let dz=-3;dz<=3;dz++) {
        const {blocks}=await server.columnProfile(hx+dx,hz+dz,hy-1,hy+14);
        const floor=blocks.find(b=>b.y===hy && b.top!=null);
        if (!floor) continue;
        const clearance=overhead(blocks,floor);
        report.hall.push({dx,dz,floor:floor.id,clearance});
        if(Number.isFinite(clearance) && clearance>=4) open.add(dx+','+dz);
      }
      const aisle=report.hall.find(({dx,dz})=>[[0,0],[1,0],[0,1],[1,1]].every(([u,v])=>open.has((dx+u)+','+(dz+v))));
      expect(aisle,'a covered two-by-two hall aisle with four blocks of headroom').toBeDefined();
      report.hallRoutes=[];
      for(const flight of report.stairs) {
        const landing=underground?flight.upperLanding:flight.lowerLanding;
        const x=hx+aisle.dx, z=hz+aisle.dz;
        await server.generate(Math.min(x,landing.x)-8,Math.min(z,landing.z)-8,
          Math.max(x,landing.x)+8,Math.max(z,landing.z)+8);
        const route=await server.walkRoute({mode:'hall',x,z,y:hy+1,endX:landing.x,endY:landing.y,endZ:landing.z,margin:8});
        report.hallRoutes.push(route);
        expect(route.found,'continuous hall clearance from the aisle to '+JSON.stringify(landing)).toBe(true);
      }
    } finally {
      writeFileSync(join(out,'circulation-'+name+'.json'),JSON.stringify(report,null,2));
    }
  });
}
