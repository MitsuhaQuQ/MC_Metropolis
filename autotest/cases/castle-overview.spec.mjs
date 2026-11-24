import {test,expect} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {ROOT,server} from '../lib/server.mjs';
import {client,clientRunning,waitForClient} from '../lib/client.mjs';

// The castle site of the castle town as a whole: an oblique view from each side and a section through the keep,
// so the baileys' levels and the walls between them can be judged by eye and by number
const town = [-10232,-10272];

async function photograph(out, info, name, x, y, z, yaw, pitch) {
  await server.command('tp @a '+[x,y,z,yaw,pitch].join(' '));
  await client.look(yaw,pitch);
  let frames=0,sections=-1;
  await waitForClient(state=>{
    frames=state.renderedSections>0 && state.renderedSections===sections ? frames+1 : 0;
    sections=state.renderedSections;
    return frames>=8 && Math.hypot(state.x-x,state.y-y,state.z-z)<1;
  },120,name);
  const path=join(out,name+'.png');
  await client.screenshot(path);
  await info.attach(name,{path,contentType:'image/png'});
}

test('castle site overview', async ({}, info) => {
  test.setTimeout(30*60*1000);
  await server.planAround(...town);
  const facilities = await server.facilities(...town);
  const keep = facilities.placed.filter(p => p.kind === 'CASTLE_PARK' && p.grade && !p.castlePart)
      .sort((a,b)=>(b.maxX-b.minX)*(b.maxZ-b.minZ)-(a.maxX-a.minX)*(a.maxZ-a.minZ))[0];
  expect(keep).toBeDefined();
  const cx=Math.round((keep.minX+keep.maxX)/2), cz=Math.round((keep.minZ+keep.maxZ)/2);
  const out = join(ROOT,'build','debug','castle-overview');
  mkdirSync(out,{recursive:true});
  const R=Number(process.env.CASTLE_RADIUS ?? 224);
  expect((await server.generate(cx-R,cz-R,cx+R,cz+R)).missing).toBe(0);
  const section=[];
  for (const [axis,dx,dz] of [['x',1,0],['z',0,1]]) {
    for (let s=-R; s<=R; s+=2) {
      const x=cx+dx*s, z=cz+dz*s;
      const p=await server.columnProfile(x,z,keep.ground-30,keep.ground+33);
      const top=p.blocks.findLast(b=>b.top!=null);
      section.push({axis,s,x,z,top:top?.top,id:top?.id});
    }
  }
  // The keep's flight: its landing (ashlar on top, at the first storey's floor) reached on foot from the court's
  // upper steps
  let landing=null;
  for (let x=keep.minX; x<=keep.maxX && !landing; x++) for (let z=keep.minZ; z<=keep.maxZ; z++) {
    const p=await server.columnProfile(x,z,keep.ground+8,keep.ground+20);
    const top=p.blocks.findLast(b=>b.top!=null && b.y<=keep.ground+18);
    const ashlar=p.blocks.find(b=>b.id==='citybuilder:stone_ashlar' && b.y>=keep.ground+10);
    if (ashlar && !p.blocks.some(b=>b.top!=null && (b.y===ashlar.y+1 || b.y===ashlar.y+2))) { landing=[x,ashlar.y+1,z]; break; }
  }
  expect(landing,'keep landing').not.toBeNull();
  const [ux,uy,uz]=keep.upperLanding;
  const climb=await server.walkRoute({x:ux,z:uz,y:uy,endX:landing[0],endY:landing[1],endZ:landing[2],margin:40});
  writeFileSync(join(out,'keep-climb.json'),JSON.stringify({landing,from:keep.upperLanding,found:climb.found,nearest:climb.nearest,steps:climb.path?.length}));
  expect(climb.found,'court to keep door, nearest '+climb.nearest).toBe(true);
  writeFileSync(join(out,'section.json'),JSON.stringify({keep:{x:cx,z:cz,y:keep.ground},section},null,1));
  if (!clientRunning()) return;
  if(!(await client.state()).inWorld) await client.join();
  await waitForClient(s=>s.inWorld,90,'world');
  await server.command('time set noon');
  await server.command('weather clear');
  await server.command('gamemode spectator @a');
  const y0=(keep.ground??80);
  for (const [name,ox,oz] of [['sw',-1,-1],['ne',1,1],['se',1,-1],['nw',-1,1]]) {
    const d=95;
    const x=cx+ox*d, z=cz+oz*d;
    const yaw=Math.atan2(-(cx-x),(cz-z))*180/Math.PI;
    await photograph(out,info,'oblique-'+name,x,y0+45,z,yaw,28);
  }
  await photograph(out,info,'low-sw',cx-120,y0+12,cz-120,-45,4);
  // The keep's battered base and its flight, from the court before it
  {
    const [lx,ly,lz]=landing;
    // Out from the keep's front (the axis the landing lies off the keep's middle along), a little to one side
    const alongZ=Math.abs(lz-cz)>Math.abs(lx-cx);
    const dx=alongZ?0:Math.sign(lx-cx), dz=alongZ?Math.sign(lz-cz):0;
    const x=lx+dx*16+dz*10, z=lz+dz*16+dx*10;
    const yaw=Math.atan2(-(lx-x),(lz-z))*180/Math.PI;
    await photograph(out,info,'keep-front',x,ly+10,z,yaw,32);
  }
  // A terrace's stone wall and its plastered wall, from the lower terrace in front of it
  let wall=null;
  search: for (const [dx,dz] of [[-1,0],[0,-1],[1,0],[0,1]]) {
    for (let r=30;r<=R-20;r++) {
      const x=cx+dx*r, z=cz+dz*r;
      const p=await server.columnProfile(x,z,keep.ground-20,keep.ground+20);
      const top=p.blocks.findLast(b=>b.top!=null);
      if (top?.id==='citybuilder:roof_kawara_slab') { wall={x,z,y:top.y,dx,dz}; break search; }
    }
  }
  writeFileSync(join(out,'wall.json'),JSON.stringify(wall));
  if (wall) {
    const x=wall.x+wall.dx*14+wall.dz*6, z=wall.z+wall.dz*14-wall.dx*6;
    const yaw=Math.atan2(-(wall.x-x),(wall.z-z))*180/Math.PI;
    await photograph(out,info,'terrace-wall',x,wall.y,z,yaw,8);
  }
});
