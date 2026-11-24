import {test,expect} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {ROOT,server} from '../lib/server.mjs';
import {client,clientRunning,waitForClient} from '../lib/client.mjs';

test('the front lots meet the tapered sidewalk at the former grass bank', async()=>{
  test.skip(process.env.AUTOTEST_LAYOUT!=='9','street-edge parcels start with layout 9');
  const samples=[[-10474,-8097],[-10476,-8091],[-10477,-8085],[-10479,-8079],[-10480,-8076]];
  await server.planAround(-10560,-8064);
  expect((await server.generate(-10486,-8100,-10465,-8070)).missing).toBe(0);
  const evidence=[];
  for(const [x,z] of samples) {
    const ground=[];
    for(let offset=0;offset<2;offset++) {
      const profile=await server.columnProfile(x+offset,z,86,96);
      ground.push(profile.blocks.findLast(b=>b.top!=null));
    }
    const lot=await server.plot(x+1.5,z+0.5);
    evidence.push({x,z,ground,plot:lot.plot,depth:lot.depth});
  }
  const dir=join(ROOT,'build','debug','urban-frontage');
  mkdirSync(dir,{recursive:true});
  writeFileSync(join(dir,'street-edge.json'),JSON.stringify(evidence,null,2));
  for(const at of evidence) {
    expect(at.ground[0].id).toBe('citybuilder:sidewalk');
    expect(at.ground[1].id).toMatch(/^(minecraft:(smooth_stone|gravel)|citybuilder:sidewalk)$/);
    expect(Math.abs(at.ground[0].top-at.ground[1].top)).toBeLessThanOrEqual(0.5);
    if(at.plot==='front') expect(at.depth).toBeLessThan(1);
  }
  expect(evidence.filter(at=>at.plot==='front').length).toBeGreaterThanOrEqual(4);
  // Along the whole frontage in view, not only the five sections: no paved column stands a step over the one
  // next to it (the apron once topped out at a whole block beside a pavement cut to eighths)
  // (a gravel car park behind follows the land's own whole-block steps, so it is not counted)
  const paved=/^(minecraft:smooth_stone|citybuilder:sidewalk)$/;
  const tops=new Map();
  for(let x=-10486;x<=-10465;x++) for(let z=-8106;z<=-8070;z++) {
    const top=(await server.columnProfile(x,z,80,100)).blocks.findLast(b=>b.top!=null);
    if(top && paved.test(top.id)) tops.set(x+','+z,{id:top.id,top:top.top});
  }
  const steps=[];
  for(const [key,a] of tops) {
    const [x,z]=key.split(',').map(Number);
    for(const [dx,dz] of [[1,0],[0,1]]) {
      const b=tops.get((x+dx)+','+(z+dz));
      if(b && Math.abs(a.top-b.top)>0.3 && Math.abs(a.top-b.top)<1.5) steps.push({x,z,dx,dz,a,b});
    }
  }
  writeFileSync(join(dir,'frontage-steps.json'),JSON.stringify({columns:tops.size,steps},null,2));
  expect(tops.size).toBeGreaterThan(150);
  expect(steps.length,JSON.stringify(steps.slice(0,5))).toBe(0);
  if(clientRunning()) {
    if(!(await client.state()).inWorld) await client.join();
    await waitForClient(s=>s.inWorld,90,'frontage world');
    await server.command('time set noon');
    await server.command('weather clear');
    await server.command('gamemode spectator @a');
    const x=-10482.7884,z=-8107.6276,yaw=6.554;
    await server.command(`tp @a ${x} 101 ${z} ${yaw} 12`);
    await client.look(yaw,12);
    let frames=0,previous=-1;
    await waitForClient(s=>{
      frames=s.renderedSections>0 && previous===s.renderedSections ? frames+1 : 0;
      previous=s.renderedSections;
      return Math.hypot(s.x-x,s.z-z)<0.2 && frames>=5;
    },90,'same camera as the grass bank');
    await client.screenshot(join(dir,'frontage.png'));
  }
});
