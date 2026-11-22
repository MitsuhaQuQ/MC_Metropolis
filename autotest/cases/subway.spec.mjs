// A subway in the game: seed 12345's city at (-18568, -19860), its own line underground through its middle. The
// stretch of it nearest an underground station, generated round; checks the box is laid (ballast at its bed, air over
// it, the roof's concrete over that); with the test client, pictures inside the box along the line, of the station's
// platform and down its entrance stair.
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const STATION = { x: -19654, z: -19821 };
const SCREENS = join(process.cwd(), 'test-results', 'screens');

async function settle() {
  let last = -1;
  let steady = 0;
  for (let i = 0; i < 90 && steady < 5; i++) {
    const { renderedSections } = await client.state();
    steady = renderedSections === last ? steady + 1 : 0;
    last = renderedSections;
    await new Promise((r) => setTimeout(r, 1000));
  }
}

async function view(name, x, y, z, yaw, pitch, testInfo) {
  await server.command(`tp @a ${x} ${y} ${z} ${yaw} ${pitch}`);
  await client.look(yaw, pitch);
  await settle();
  const file = join(SCREENS, `${name}.png`);
  await client.screenshot(file);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

async function is(x, y, z, block) {
  return JSON.stringify(await server.command(`execute if block ${x} ${y} ${z} ${block}`)).includes('passed');
}

test('a subway runs under the city', async ({}, testInfo) => {
  test.setTimeout(1_800_000);
  await server.planAround(STATION.x, STATION.z);
  const p = await server.rail(STATION.x, STATION.z, 'subway');
  console.log('subway', JSON.stringify(p));
  expect(p.found).toBe(true);
  const s = await server.rail(STATION.x, STATION.z, 'substation');
  console.log('station', JSON.stringify(s));
  const x = Math.round(p.x);
  const z = Math.round(p.z);
  await server.generate(x - 40, z - 40, x + 39, z + 39);
  const bed = Math.floor(p.level);
  const found = {};
  for (let y = bed - 1; y <= bed + 7; y++) {
    for (const b of ['minecraft:gravel', 'minecraft:air', 'minecraft:light_gray_concrete', 'citybuilder:ceiling_light', 'minecraft:stone']) {
      if (await is(x, y, z, b)) {
        found[y - bed] = b;
      }
    }
  }
  console.log('column from the bed down one', JSON.stringify(found));
  expect(found[0]).toBe('minecraft:gravel');
  expect(found[3]).toBe('minecraft:air');
  if (clientRunning()) {
    if (!(await client.state()).inWorld) {
      await client.join();
      await waitForClient((st) => st.inWorld, 300, 'in the world');
    }
    await server.command('gamemode spectator @a');
    await server.command('time set noon');
    const h = p.heading * Math.PI / 180;
    const along = (Math.atan2(-Math.cos(h), Math.sin(h)) * 180) / Math.PI;
    await view('subway-box', x, bed + 2, z, Math.round(along), 0, testInfo);
    if (s.found) {
      const sx = Math.round(s.x);
      const sz = Math.round(s.z);
      await server.generate(sx - 40, sz - 40, sx + 39, sz + 39);
      const sh = s.heading * Math.PI / 180;
      const salong = (Math.atan2(-Math.cos(sh), Math.sin(sh)) * 180) / Math.PI;
      const sbed = Math.floor(s.level);
      // The nearest entrance stair: its steps (the test world's only: turning them all one way counts them)
      const e = await server.rail(sx, sz, 'entrance');
      console.log('entrance', JSON.stringify(e));
      expect(e.found).toBe(true);
      const ex0 = Math.round(e.x);
      const ez0 = Math.round(e.z);
      await server.generate(ex0 - 48, ez0 - 48, ex0 + 47, ez0 + 47);
      let steps = 0;
      for (let dx = -48; dx < 48; dx += 24) {
        for (let dz = -48; dz < 48; dz += 24) {
          for (const facing of ['north', 'south']) {
            const r = JSON.stringify(await server.command(`fill ${ex0 + dx} ${Math.floor(e.level)} ${ez0 + dz} ${ex0 + dx + 23} ${Math.floor(e.level) + 30} ${ez0 + dz + 23} minecraft:stone_brick_stairs[facing=${facing}] replace minecraft:stone_brick_stairs`));
            const m = r.match(/filled (\d+)/);
            steps += m ? Number(m[1]) : 0;
          }
        }
      }
      console.log('entrance steps found', steps);
      expect(steps).toBeGreaterThan(3);
      const eh = e.heading * Math.PI / 180;
      const eAlong = (Math.atan2(-Math.cos(eh), Math.sin(eh)) * 180) / Math.PI;
      await view('subway-entrance', Math.round(ex0 + Math.cos(eh) * 30), Math.round(e.ground) + 6,
        Math.round(ez0 + Math.sin(eh) * 30), Math.round(eAlong + 180), 35, testInfo);
      // On the platform's edge, looking along it
      const px = sx - Math.sin(sh) * 6;
      const pz = sz + Math.cos(sh) * 6;
      await view('subway-station', Math.round(px), sbed + 3, Math.round(pz), Math.round(salong), 5, testInfo);

    }
  }
});
