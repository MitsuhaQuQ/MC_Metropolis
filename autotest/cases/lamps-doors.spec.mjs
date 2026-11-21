// The buildings' lamps and doors in the game: every lamp in each of its four facings, every door in every state (each
// facing, hinge, shut and open, both halves), set high over spawn with setblock (every state must be accepted); the
// test client looks at them from the front, at eye level, and from above. Skipped without the test client
// (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250;
const LAMPS = ['chochin_red', 'chochin_white', 'andon', 'andon_sign', 'porch_light_black', 'porch_light_silver',
  'gate_light', 'toro_light'];
const DOORS = ['koshi_door', 'entrance_door_silver', 'entrance_door_bronze', 'shop_door'];
const FACINGS = ['south', 'east', 'north', 'west'];

async function set(x, y, z, state) {
  const r = await server.command(`setblock ${x} ${y} ${z} ${state}`);
  expect(JSON.stringify(r), state).not.toMatch(/Unknown|Expected|Invalid|Incorrect|Could not/);
}

async function settle() {
  let last = -1;
  let steady = 0;
  for (let i = 0; i < 60 && steady < 4; i++) {
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
  expect(statSync(file).size).toBeGreaterThan(10000);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('lamps and doors in the game', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(600_000);
  await server.generate(-48, -48, 47, 47);
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set 18000');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  await server.command(`fill -4 ${Y - 1} -4 36 ${Y - 1} 24 minecraft:smooth_stone`);
  // Lamps: a column of each, one facing per row, a wall behind the row (a porch light's plate against it) and a
  // beam over it (a lantern's cord up to it)
  for (let i = 0; i < LAMPS.length; i++) {
    for (let j = 0; j < FACINGS.length; j++) {
      const x = 2 * i, z = 3 * j;
      await set(x, Y, z, `citybuilder:${LAMPS[i]}[facing=${FACINGS[j]}]`);
    }
  }
  await server.command(`fill -1 ${Y + 1} -1 16 ${Y + 1} 10 minecraft:dark_oak_slab[type=bottom]`);
  // Doors: every facing, hinge and open state of each, both halves, two blocks apart
  for (let i = 0; i < DOORS.length; i++) {
    let k = 0;
    for (const facing of FACINGS) {
      for (const hinge of ['left', 'right']) {
        for (const open of ['false', 'true']) {
          const x = 2 * k++, z = 14 + 3 * i;
          const props = `facing=${facing},hinge=${hinge},open=${open}`;
          await set(x, Y, z, `citybuilder:${DOORS[i]}[${props},half=lower]`);
          await set(x, Y + 1, z, `citybuilder:${DOORS[i]}[${props},half=upper]`);
        }
      }
    }
  }
  // Every door is two halves still (a lower half alone would have dropped)
  for (let i = 0; i < DOORS.length; i++) {
    const r = await server.command(`fill 0 ${Y + 1} ${14 + 3 * i} 30 ${Y + 1} ${14 + 3 * i} minecraft:air replace citybuilder:${DOORS[i]}[half=upper]`);
    expect(JSON.stringify(r), DOORS[i]).toMatch(/filled 16 block/);
    let k = 0;
    for (const facing of FACINGS) {
      for (const hinge of ['left', 'right']) {
        for (const open of ['false', 'true']) {
          const x = 2 * k++, z = 14 + 3 * i;
          const props = `facing=${facing},hinge=${hinge},open=${open}`;
          await set(x, Y, z, `citybuilder:${DOORS[i]}[${props},half=lower]`);
          await set(x, Y + 1, z, `citybuilder:${DOORS[i]}[${props},half=upper]`);
        }
      }
    }
  }
  await view('lamps-front', 7, Y + 1, -5, 0, 15, testInfo);
  await view('lamps-above', 7, Y + 7, 14, 180, 40, testInfo);
  await view('doors-front', 15, Y + 2, 8, 0, 20, testInfo);
  await view('doors-above', 15, Y + 9, 30, 180, 45, testInfo);
  await server.command('time set 6000');
  await view('lamps-day', 7, Y + 1, -5, 0, 15, testInfo);
});
