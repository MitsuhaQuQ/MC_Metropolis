import {test,expect} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {ROOT,server} from '../lib/server.mjs';
import {client,clientRunning,waitForClient} from '../lib/client.mjs';

const town = [-10232,-10272];

async function photograph(out, info, name, x, y, z, yaw, pitch) {
  await server.command('tp @a '+[x,y,z,yaw,pitch].join(' '));
  await client.look(yaw,pitch);
  let frames=0,sections=-1;
  await waitForClient(state=>{
    frames=state.renderedSections>0 && state.renderedSections===sections ? frames+1 : 0;
    sections=state.renderedSections;
    return frames>=6 && Math.hypot(state.x-x,state.y-y,state.z-z)<1;
  },90,name);
  const path=join(out,name+'.png');
  await client.screenshot(path);
  await info.attach(name,{path,contentType:'image/png'});
}

test('castle moat crossings, corner turrets and upper court are built at bailey levels',async ({}, info) => {
  await server.planAround(...town);
  const plan = await server.castle(...town);
  expect(plan.found).toBe(true);
  expect(plan.rings).toBeGreaterThanOrEqual(2);
  const facilities = await server.facilities(...town);
  const built = facilities.placed.filter(p => p.kind === 'CASTLE_PARK');
  const keep = built.find(p => p.grade && !p.castlePart && p.modelHeight >= 47);
  const gates = built.filter(p => p.castlePart === 'GATE');
  const turrets = built.filter(p => ['EAST','SOUTHEAST','SOUTHWEST','SMALL'].includes(p.castlePart));
  const galleries = built.filter(p => ['WATARI','WEST_GALLERY','WATARI_WING'].includes(p.castlePart));
  const out = join(ROOT,'build','debug','castle-wards');
  mkdirSync(out,{recursive:true});
  const report = {plan,keep,gates,turrets,galleries,crossings:[]};
  try {
    expect(keep).toBeDefined();
    expect(gates.length).toBeGreaterThanOrEqual(2);
    expect(turrets.length).toBeGreaterThanOrEqual(2);
    expect(galleries.length,'a castle with room for a covered gallery').toBeGreaterThan(0);
    for (const part of [keep,gates[0],turrets[0],galleries[0]]) {
      expect((await server.generate(part.minX-3,part.minZ-3,part.maxX+3,part.maxZ+3)).missing).toBe(0);
      const top = (await server.blocks(part.minX,part.minZ,part.maxX,part.maxZ)).top;
      const tile = Object.entries(top).filter(([id])=>id.includes('deepslate_tile')).reduce((n,[,v])=>n+v,0);
      expect(tile,'roof missing at '+JSON.stringify(part)).toBeGreaterThan(6);
    }
    for (const bridge of plan.bridges.slice(0,8)) {
      const x=Math.floor(bridge.x),z=Math.floor(bridge.z),h=Math.floor(bridge.deck);
      await server.generate(x-14,z-14,x+14,z+14);
      const profile=await server.columnProfile(x,z,h-20,h+12);
      const deck=profile.blocks.find(b=>b.id==='minecraft:spruce_planks');
      report.crossings.push({x,z,deck,blocks:profile.blocks});
      expect(deck,'bridge deck at '+x+','+z).toBeDefined();
      expect(Math.abs(deck.y-h)).toBeLessThanOrEqual(1);
      if (bridge.wet) expect(profile.blocks.some(b=>b.y<deck.y&&b.id==='minecraft:water')).toBe(true);
    }
    expect(report.crossings.length).toBeGreaterThanOrEqual(2);
    const [endX,endY,endZ]=keep.upperLanding;
    const sides = [
      {distance:endX-keep.minX,dx:-1,dz:0}, {distance:keep.maxX-endX,dx:1,dz:0},
      {distance:endZ-keep.minZ,dx:0,dz:-1}, {distance:keep.maxZ-endZ,dx:0,dz:1}
    ];
    const {distance,dx,dz}=sides.sort((a,b)=>a.distance-b.distance)[0];
    const gateX=endX+dx*distance, gateZ=endZ+dz*distance;
    const floor=keep.ground+2;
    report.mainGate={gateX,gateZ,columns:[]};
    for(let inward=3;inward<12;inward++) {
      for(let cross=-3;cross<=3;cross++) {
        const x=gateX-dx*inward-dz*cross, z=gateZ-dz*inward+dx*cross;
        const column=await server.columnProfile(x,z,floor,floor+12);
        const pavement=column.blocks.find(b=>b.y===floor);
        const beam=column.blocks.find(b=>b.y>floor&&b.bottom!=null);
        expect(pavement?.top,'gate floor at '+[x,z]).toBe(floor+1);
        expect(beam,'gate overhead structure at '+[x,z]).toBeDefined();
        report.mainGate.columns.push({x,z,clearance:beam.bottom-pavement.top});
      }
    }
    expect(Math.min(...report.mainGate.columns.map(c=>c.clearance))).toBeGreaterThanOrEqual(6);
    const entrances=plan.bridges.filter(b=>!b.inner).sort((a,b)=>Math.hypot(a.x-endX,a.z-endZ)-Math.hypot(b.x-endX,b.z-endZ));
    report.walks=[];
    for(const gate of entrances) {
      const x=Math.floor(gate.x),z=Math.floor(gate.z),y=Math.floor(gate.deck)+1,margin=48;
      await server.generate(Math.min(x,endX)-margin,Math.min(z,endZ)-margin,Math.max(x,endX)+margin,Math.max(z,endZ)+margin);
      const route=await server.walkRoute({x,z,y,endX,endY,endZ,margin});
      report.walks.push({x,y,z,route});
      if (!report.walk) report.walk=route;
      expect(route.found,'outer moat bridge '+[x,z]+' to upper court, nearest '+route.nearest).toBe(true);
      expect(endY-y).toBeGreaterThan(2);
    }
    report.masonry=[];
    expect(plan.seams.length).toBeGreaterThanOrEqual(2);
    for (const [a,b] of plan.seams) {
      await server.generate(a[0],a[2],b[0],b[2]);
      for(const [x,y,z] of [a,b]) {
        const col=await server.columnProfile(x,z,y,y+1);
        report.masonry.push({x,y,z,block:col.blocks[0].id});
        expect(col.blocks[0].id,'stone wall at chunk boundary '+[x,y,z]).toBe('minecraft:stone_bricks');
      }
    }
    report.roofs=[];
    for(const gate of gates) {
      // Generate the whole neighbourhood: decorating the next chunk clears obstructions
      // over streets in this chunk too, after the bridgehead gate may already be built.
      await server.generate(gate.minX-24,gate.minZ-24,gate.maxX+24,gate.maxZ+24);
      expect(gate.crown?.length,'roof crosses the centre of the gateway').toBeGreaterThan(12);
      for(const block of gate.crown) {
        const column=await server.columnProfile(block.x,block.z,block.y,block.y);
        const actual=column.blocks[0].id;
        report.roofs.push({...block,actual});
        expect(actual,'gate roof after adjacent chunk decoration at '+[block.x,block.y,block.z]).toBe(block.id);
      }
    }
    if (clientRunning()) {
      if (!(await client.state()).inWorld) { await client.join(); await waitForClient(s=>s.inWorld,180,'castle world'); }
      await server.command('gamemode spectator @a');await server.command('time set 3000');
      await server.command('gamerule doDaylightCycle false');await server.command('weather clear');
      for(const [name,part] of [['turret',turrets[0]],['gallery',galleries[0]],['keep',keep],['gate',gates[0]]]){
        const x=(part.minX+part.maxX)/2,z=(part.minZ+part.maxZ)/2,back=Math.min(56,Math.max(23,part.maxX-part.minX));
        await photograph(out,info,name,x+back,part.ground+back*0.7,z+back,135,19);
      }
      const yaw=Math.atan2(dx,-dz)*180/Math.PI;
      await photograph(out,info,'main-gate',gateX+0.5+16*dx,keep.ground+1,gateZ+0.5+16*dz,yaw,-10);
      if(process.env.AUTOTEST_PREVIEW_SPAWN==='castle') {
        const x=gateX+16*dx,z=gateZ+16*dz;
        const {blocks}=await server.columnProfile(x,z,keep.ground-6,keep.ground+6);
        const paving=blocks.findLast(b=>b.top!=null && /gravel|sidewalk|stone_bricks|dirt_path/.test(b.id));
        expect(paving,'standing surface for the preview at the castle gate').toBeDefined();
        await server.command('gamerule spawnRadius 0');
        await server.command('defaultgamemode creative');
        await server.command('setworldspawn '+[x,Math.ceil(paving.top),z,yaw].join(' '));
        report.previewSpawn={x,y:Math.ceil(paving.top),z,yaw};
      }
      const bridge=report.walks[0];
      const target=gates.map(g=>[(g.minX+g.maxX+1)/2,(g.minZ+g.maxZ+1)/2])
        .sort((a,b)=>Math.hypot(a[0]-bridge.x,a[1]-bridge.z)-Math.hypot(b[0]-bridge.x,b[1]-bridge.z))[0];
      const bridgeYaw=Math.atan2(bridge.x+0.5-target[0],target[1]-bridge.z-0.5)*180/Math.PI;
      await photograph(out,info,'outer-approach',bridge.x+0.5,bridge.y,bridge.z+0.5,bridgeYaw,-5);
    }
  } finally { writeFileSync(join(out,'wards.json'),JSON.stringify(report,null,2)); }
});
test('a castle park without a keep still has a walled entrance and upper court',async({},info)=>{
  const place=await server.castleWithoutKeep(...town);
  expect(place.found,'locate a real castle town without a keep').toBe(true);
  const plan=await server.castle(place.x,place.z);
  const all=(await server.facilities(place.x,place.z)).placed.filter(p=>p.kind==='CASTLE_PARK');
  expect(plan.rings).toBeGreaterThanOrEqual(2);
  expect(all.some(p=>p.modelHeight>=47&&!p.castlePart)).toBe(false);
  const gate=all.find(p=>p.castlePart==='GATE');
  expect(gate).toBeDefined();
  const court=all.find(p=>p.upperLanding && !p.castlePart);
  expect(court,'a terraced court in the castle without a keep').toBeDefined();
  const [x,y,z]=court.upperLanding;
  await server.generate(x-2,z-2,x+2,z+2);
  const landing=await server.columnProfile(x,z,y-1,y+2);
  expect(landing.blocks.find(b=>b.y===y-1)?.id).toBe('minecraft:stone_brick_stairs');
  expect(y-court.ground).toBeGreaterThan(3);
  await server.generate(gate.minX-6,gate.minZ-6,gate.maxX+6,gate.maxZ+6);
  const top=(await server.blocks(gate.minX,gate.minZ,gate.maxX,gate.maxZ)).top;
  expect(Object.entries(top).filter(([k])=>k.includes('deepslate_tile')).reduce((n,[,v])=>n+v,0)).toBeGreaterThan(6);
  const out=join(ROOT,'build','debug','castle-wards');
  mkdirSync(out,{recursive:true});writeFileSync(join(out,'without-keep.json'),JSON.stringify({place,gate,court,landing,plan},null,2));
  if (clientRunning()) {
    if (!(await client.state()).inWorld) { await client.join(); await waitForClient(s=>s.inWorld,180,'castle world'); }
    await server.command('gamemode spectator @a');
    const cx=(court.minX+court.maxX)/2,cz=(court.minZ+court.maxZ)/2;
    const eyeX=court.maxX+15,eyeZ=cz+12;
    const yaw=Math.atan2(eyeX-cx,cz-eyeZ)*180/Math.PI;
    await photograph(out,info,'without-keep',eyeX,court.ground+8,eyeZ,yaw,7);
  }
});
