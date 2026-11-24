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
});
