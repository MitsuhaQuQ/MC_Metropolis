// The main station's square of the castle town (seed 12345), laid on the land the streets leave in front of the
// station: paved, and with the test client pictures from above, from over the avenue and from its island.
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { mkdirSync, statSync, writeFileSync } from 'node:fs';
import { ROOT, server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const TOWN = { x: -10232, z: -10272 };
const SCREENS = join(ROOT, 'build', 'debug', 'station-scale', 'layout-'+(process.env.AUTOTEST_LAYOUT??'8'));

async function settle() {
  let last = -1;
  let steady = 0;
  await waitForClient(state => {
    steady = state.renderedSections > 0 && state.renderedSections === last ? steady + 1 : 0;
    last = state.renderedSections;
    return steady >= 5;
  }, 90, 'station scene rendered');
}

async function view(name, x, y, z, yaw, pitch, testInfo) {
  await server.command(`tp @a ${x} ${y} ${z} ${yaw} ${pitch}`);
  await client.look(yaw, pitch);
  await settle();
  const file = join(SCREENS, `${name}.png`);
  await client.screenshot(file);
  expect(statSync(file).size).toBeGreaterThan(10000);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('the station square on its own land', async ({}, testInfo) => {
  const st = await server.station(TOWN.x, TOWN.z);
  console.log('station', st.x, st.z, st.template);
  expect(st.found).toBe(true);
  const [sx, sz] = st.middle;
  const x1 = Math.round(sx) - 72;
  const z1 = Math.round(sz) - 72;
  await server.generate(x1, z1, x1 + 143, z1 + 143);
  const blocks = await server.blocks(x1, z1, x1 + 143, z1 + 143);
  console.log('square blocks', JSON.stringify(blocks).slice(0, 900));
  const sg = await server.station(TOWN.x, TOWN.z);
  expect(Number.isFinite(sg.ground)).toBe(true);
  const ground = sg.ground;
  const output = join(ROOT, 'build', 'debug', 'station-scale', 'layout-' + (process.env.AUTOTEST_LAYOUT ?? '8'));
  mkdirSync(output, { recursive: true });
  writeFileSync(join(output,'plan.json'),JSON.stringify(sg,null,2));
  if (Number(process.env.AUTOTEST_LAYOUT ?? 8)>=8) {
    expect(sg.driveStarts.length,'main avenue must meet a verified plaza approach').toBe(2);
    const bus=sg.bays.filter(b=>b.use==='BUS');
    expect(bus.length,'ready-to-use bus stops').toBeGreaterThan(0);
    const routes=[];
    for(const b of sg.bays) {
      const [x,z]=b.road.map(Math.floor);
      for(const start of sg.driveStarts) {
        const [sx,sz]=start.map(Math.floor);
        await server.generate(Math.min(sx,x)-24,Math.min(sz,z)-24,Math.max(sx,x)+24,Math.max(sz,z)+24);
        const roadFeet=async (cx,cz)=>{
          const {blocks}=await server.columnProfile(cx,cz,ground-12,ground+12);
          const floor=blocks.findLast(b=>b.top!=null&&/citybuilder:(asphalt|manhole|drain_grate)/.test(b.id)
            && b.top+4<=blocks.at(-1).y+1 && !blocks.some(o=>o.y>b.y&&o.bottom!=null&&o.bottom<b.top+4));
          expect(floor,'clear driving surface at '+[cx,cz]).toBeDefined();
          return Math.round(floor.top);
        };
        const route=await server.walkRoute({mode:'drive',x:sx,z:sz,y:await roadFeet(sx,sz),
          endX:x,endZ:z,endY:await roadFeet(x,z),margin:20});
        const result = {use:b.use,start,road:b.road,route};
        if (!route.found) {
          const [mx,mz]=sg.mouth.map(Math.floor);
          result.mouthProfile=[];
          const heading=sg.outward*Math.PI/180;
          for(let d=-12;d<=12;d++) {
            const cx=Math.floor(mx+0.5+d*Math.cos(heading)),cz=Math.floor(mz+0.5+d*Math.sin(heading));
            result.mouthProfile.push(await server.columnProfile(cx,cz,ground-6,ground+6,true));
          }
        }
        routes.push(result);
        writeFileSync(join(output,'drive.json'),JSON.stringify(routes,null,2));
        expect.soft(route.found,'avenue to '+b.use+' berth '+JSON.stringify(route.nearest)).toBe(true);
      }
    }
    const [ex,ez]=sg.entryOuter.map(Math.floor);
    const walks=[];
    for (const bay of sg.bays) {
      const [wx,wz]=bay.walk.map(Math.floor);
      await server.generate(Math.min(ex,wx)-32,Math.min(ez,wz)-32,Math.max(ex,wx)+32,Math.max(ez,wz)+32);
      const walk=await server.walkRoute({mode:'station-walk',x:ex,z:ez,y:ground,endX:wx,endZ:wz,endY:ground,margin:32});
      walks.push({use:bay.use,walk});
      if (bay===bus[0]) writeFileSync(join(output,'walk-to-berth.json'),JSON.stringify(walk,null,2));
      writeFileSync(join(output,'walk-to-berths.json'),JSON.stringify(walks,null,2));
      expect.soft(walk.found,'forecourt to '+bay.use+' on pedestrian paving').toBe(true);
    }
  }
  const [ix, iz] = sg.entryInner, [ox, oz] = sg.entryOuter;
  const distance = Math.hypot(ox - ix, oz - iz);
  const dx = (ix - ox) / distance, dz = (iz - oz) / distance;
  const approaches = [];
  // The low entrance occupies part of the inner lawn; inspect the exposed shoulders outside its building.
  let shoulder = sg.lowerEntrance?.length ? 24 : 6;
  if (sg.lowerEntrance?.length && Number(process.env.AUTOTEST_LAYOUT ?? 8)>=8) {
    // The stair and its walls extend on both sides of the doorway. Sample the exposed
    // ground beyond their actual footprint, including where the flight is mirrored.
    shoulder = Math.ceil(Math.max(...sg.lowerEntrance.map(([x,,z])=>Math.abs(-(x-ox)*dz+(z-oz)*dx)))+4);
  }
  for (const side of [-shoulder, -2, 0, 2, shoulder]) {
    const steps = [];
    for (let i = -2; i <= Math.ceil(distance) + 1; i++) {
      const x = Math.floor(ox + i * dx - side * dz), z = Math.floor(oz + i * dz + side * dx);
      const column = await server.columnProfile(x, z, Math.floor(ground) - 14, Math.floor(ground) + 14);
      expect(column.loaded).toBe(true);
      const previous = steps.at(-1)?.feet ?? ground;
      const walkable = column.blocks.filter(b => b.top != null && b.top+2<=column.blocks.at(-1).y+1 && !column.blocks.some(o =>
        o.bottom != null && o.y !== b.y && o.bottom < b.top + 2 && o.top > b.top))
        .sort((a, b) => Math.abs(a.top - previous) - Math.abs(b.top - previous))[0];
      steps.push({ x, z, feet: walkable?.top, id: walkable?.id, rise: walkable ? Math.abs(walkable.top - previous) : null });
    }
    approaches.push({ side, steps });
  }
  writeFileSync(join(output, 'approach.json'), JSON.stringify(approaches, null, 2));
  if (Number(process.env.AUTOTEST_LAYOUT ?? 8) >= 5) {
    const route = sg.lowerEntrance;
    expect(route.length, 'this high main station must have a lower entrance').toBeGreaterThan(12);
    const measured = [];
    for (const [wx, expected, wz] of route) {
      const x = Math.floor(wx), z = Math.floor(wz);
      const profile = await server.columnProfile(x, z, expected - 3, expected + 6);
      expect(profile.loaded).toBe(true);
      const floor = profile.blocks.filter(b => b.top != null && Math.abs(b.top - expected) <= 0.51
        && !profile.blocks.some(o => o.y !== b.y && o.bottom != null && o.bottom < b.top + 2 && o.top > b.top))
        .sort((a, b) => Math.abs(a.top - expected) - Math.abs(b.top - expected))[0];
      const ceiling = floor && profile.blocks.find(b => b.y > floor.y && b.bottom != null);
      measured.push({ x, z, expected, feet: floor?.top, headroom: floor && ceiling ? ceiling.bottom - floor.top : null,
        floor: floor?.id, ceiling: ceiling?.id, blocks: profile.blocks });
    }
    writeFileSync(join(output, 'lower-entrance.json'), JSON.stringify(measured, null, 2));
    for (const at of measured.slice(0, 3)) {
      expect(at.floor, 'the threshold must meet the plaza on paving').toMatch(/andesite|smooth_stone|stone_bricks/);
    }
    for (const [i, at] of measured.entries()) {
      expect(at.feet, 'missing walking surface ' + JSON.stringify(at)).toBeDefined();
      if (at.headroom != null) expect(at.headroom, 'obstruction at ' + at.x + ',' + at.z).toBeGreaterThanOrEqual(2);
      if (i) expect(Math.abs(at.feet - measured[i - 1].feet)).toBeLessThanOrEqual(1);
    }
    const roadClearances = [];
    for (const at of measured.filter(p => p.expected === measured.at(-1).expected)) {
      const blocks=[];
      const low=Math.min(measured[0].expected-8,at.expected-32);
      for(let y=low;y<=at.expected;y+=64) {
        const column=await server.columnProfile(at.x,at.z,y,Math.min(y+63,at.expected));
        blocks.push(...column.blocks);
      }
      const road = blocks.findLast(b => b.top != null && b.id.startsWith('citybuilder:asphalt'));
      if (road) {
        const overhead = blocks.find(b => b.bottom != null && b.y > road.y);
        if (overhead) roadClearances.push({ x: at.x, z: at.z, road: road.top, clearance: overhead.bottom - road.top });
      }
    }
    writeFileSync(join(output, 'street-below.json'), JSON.stringify(roadClearances, null, 2));
    expect(roadClearances.length, 'this gallery crosses the upper street').toBeGreaterThan(0);
    for (const at of roadClearances) expect(at.clearance, JSON.stringify(at)).toBeGreaterThanOrEqual(4);
    expect(measured.at(-1).feet - measured[0].feet).toBeGreaterThan(4);
  }
  if (Number(process.env.AUTOTEST_LAYOUT ?? 8) >= 4) {
    for (const lane of approaches) {
      for (const [index, step] of lane.steps.entries()) {
        if (index === 0) continue;
        expect(step.rise, 'missing clear approach at ' + JSON.stringify(step)).not.toBeNull();
        expect(step.rise, 'discontinuous approach at ' + JSON.stringify(step)).toBeLessThanOrEqual(1);
        if (Math.abs(lane.side) === shoulder && step !== lane.steps.at(-1)) {
          const centre = approaches.find(l => l.side === 0).steps[index];
          expect(Math.abs(step.feet - centre.feet), 'exposed cut beside station approach').toBeLessThanOrEqual(2);
        }
      }
    }
  }
  if (!clientRunning()) return;
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  // Same point relative to the track centre in both layouts. Resolve the actual ground below the eye.
  const outward = st.outward * Math.PI / 180;
  const cameraX = Math.floor(st.x + 72 * Math.cos(outward));
  const cameraZ = Math.floor(st.z + 72 * Math.sin(outward));
  await server.generate(cameraX - 8, cameraZ - 8, cameraX + 7, cameraZ + 7);
  const profile = await server.columnProfile(cameraX, cameraZ, Math.floor(ground) - 14, Math.floor(ground) + 15);
  expect(profile.loaded).toBe(true);
  const floor = profile.blocks.filter(b => b.top != null && Math.abs(b.top - ground) <= 4)
    .sort((a, b) => b.top - a.top)[0];
  expect(floor, 'station approach needs a standing surface').toBeDefined();
  const yaw = Math.atan2(Math.cos(outward), -Math.sin(outward)) * 180 / Math.PI;
  await server.command('tp @a ' + [cameraX + 0.5, floor.top, cameraZ + 0.5, yaw, 0].join(' '));
  await client.look(yaw, 0);
  await waitForClient(s => Math.abs(s.x - cameraX - 0.5) < 0.1 && Math.abs(s.y - floor.top) < 0.1
    && Math.abs(s.z - cameraZ - 0.5) < 0.1 && s.renderedSections > 0, 90, 'station entrance camera');
  await settle();
  const file = join(output, 'station-front.png');
  await client.screenshot(file);
  expect(statSync(file).size).toBeGreaterThan(10000);
  await testInfo.attach('station-front', { path: file, contentType: 'image/png' });
  writeFileSync(join(output, 'station.json'), JSON.stringify({ station: st, cameraX, cameraZ, floor, yaw, profile }, null, 2));
  // Inspect the pedestrian landing itself as well as the matched view from across the square.
  const landing = approaches.find(lane => lane.side === 0).steps[0];
  await server.command('tp @a ' + [landing.x + 0.5, landing.feet, landing.z + 0.5, yaw, 0].join(' '));
  await client.look(yaw, 0);
  await waitForClient(s => Math.abs(s.x - landing.x - 0.5) < 0.1 && Math.abs(s.y - landing.feet) < 0.1
    && Math.abs(s.z - landing.z - 0.5) < 0.1 && s.renderedSections > 0, 90, 'pedestrian landing');
  await settle();
  const entry = join(output, 'pedestrian-entry.png');
  await client.screenshot(entry);
  expect(statSync(entry).size).toBeGreaterThan(10000);
  await testInfo.attach('pedestrian-entry', { path: entry, contentType: 'image/png' });
  if (sg.lowerEntrance?.length) {
    const route = sg.lowerEntrance, [doorX, doorY, doorZ] = route[0];
    const foot = route.findIndex((at, i) => i && at[1] > route[i - 1][1]);
    const inside = route[Math.max(0, foot - 2)], next = route[foot];
    const views = [
      ['lower-door', doorX + 4 * Math.cos(outward), doorY, doorZ + 4 * Math.sin(outward), yaw],
      ['lower-foyer', ...inside, Math.atan2(inside[0] - next[0], next[2] - inside[2]) * 180 / Math.PI],
    ];
    for (const [name, x, y, z, look] of views) {
      await server.command('tp @a ' + [x, y, z, look, 0].join(' '));
      await client.look(look, 0);
      await waitForClient(s => Math.hypot(s.x - x, s.y - y, s.z - z) < 0.2, 60, name);
      await settle();
      const shot = join(output, name + '.png');
      await client.screenshot(shot);
      expect(statSync(shot).size).toBeGreaterThan(10000);
      await testInfo.attach(name, { path: shot, contentType: 'image/png' });
    }
  }
  // Match the original screenshot even when a corrected entrance changes the plaza midpoint.
  await view('square-above', -10483.5, 185, -8038.5, 0, 90, testInfo);
  if (sg.roadsideLayout==='PULL_OUT') {
    const [bx,bz]=sg.bays.find(b=>b.use==='BUS').road;
    await view('stopping-lane',bx,ground+55,bz,0,90,testInfo);
    const h=sg.heading*Math.PI/180, n=sg.outward*Math.PI/180;
    const x=sg.x-44*Math.cos(h)+77*Math.cos(n),z=sg.z-44*Math.sin(h)+77*Math.sin(n);
    const yaw=Math.atan2(-Math.cos(h)+0.11*Math.cos(n),Math.sin(h)-0.11*Math.sin(n))*180/Math.PI;
    await view('lane-merge',x,ground+10,z,yaw,12,testInfo);
  }
  const oh = (st.outward * Math.PI) / 180;
  const [mx, mz] = st.mouth;
  const back = (Math.atan2(Math.cos(oh), -Math.sin(oh)) * 180) / Math.PI;
  await view('square-throat', mx + 30 * Math.cos(oh), ground + 25, mz + 30 * Math.sin(oh), back, 30, testInfo);
  const [lx, lz] = st.loop;
  await view('square-island', lx, ground + 3, lz, back + 180, 15, testInfo);
});
