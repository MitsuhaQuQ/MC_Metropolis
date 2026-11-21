// The buildings' fixtures in the game: every state of each (each facing; an outdoor unit's three mounts, a two-block
// fixture's two halves; a louvre's two axes), set high over spawn with setblock (every state must be accepted), a
// wall behind them; the test client looks at them from the front, from above and at night. Skipped without the test
// client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250;
const FACINGS = ['north', 'east', 'south', 'west'];
// One row per kind: each block, its states as property strings without facing
const ROWS = [
  [['ac_unit_white', ['mount=floor', 'mount=wall', 'mount=roof']], ['ac_unit_beige', ['mount=floor', 'mount=wall', 'mount=roof']]],
  [['vending_red', ['tall']], ['vending_blue', ['tall']], ['vending_white', ['tall']], ['water_heater', ['tall']]],
  [['postbox', ['']], ['downpipe_gray', ['']], ['downpipe_beige', ['']], ['downpipe_brown', ['']], ['downpipe_black', ['']],
    ['solar_panel', ['']], ['tv_antenna', ['']]],
];

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

test('fixtures in the game', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(600_000);
  await server.generate(-48, -48, 63, 63);
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set 6000');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  await server.command(`fill -4 ${Y - 1} -4 60 ${Y + 3} 60 minecraft:air`);
  await server.command(`fill -4 ${Y - 1} -4 60 ${Y - 1} 60 minecraft:smooth_stone`);
  // Each row: the facings one behind another (north first), each block's states side by side, two apart; facing
  // north, a wall behind (to the south) of each, so a hung unit's brackets and a downpipe's clamp have their wall
  let z0 = 0;
  for (const row of ROWS) {
    for (let j = 0; j < FACINGS.length; j++) {
      const z = z0 + 4 * j;
      let x = 0;
      for (const [name, states] of row) {
        for (const s of states) {
          const facing = FACINGS[j];
          if (s === 'tall') {
            await set(x, Y, z, `citybuilder:${name}[facing=${facing},half=lower]`);
            await set(x, Y + 1, z, `citybuilder:${name}[facing=${facing},half=upper]`);
          } else {
            const y = s === 'mount=wall' ? Y + 1 : Y;
            await set(x, y, z, `citybuilder:${name}[facing=${facing}${s ? ',' + s : ''}]`);
          }
          // The wall behind, on the side its back is to
          const back = { north: [0, 1], south: [0, -1], east: [-1, 0], west: [1, 0] }[facing];
          await server.command(`fill ${x + back[0]} ${Y} ${z + back[1]} ${x + back[0]} ${Y + 2} ${z + back[1]} minecraft:white_concrete keep`);
          x += 3;
        }
      }
    }
    z0 += 16;
  }
  // Louvres: a strip of each axis, as a covered street's roof
  for (let i = 0; i < 6; i++) {
    await set(30 + i, Y + 2, 0, 'citybuilder:arcade_louvre[axis=x]');
    await set(30 + i, Y + 2, 2, 'citybuilder:arcade_louvre[axis=z]');
  }
  // Street lights: a pole four segments tall under each head (one facing per pole) and a lane light on a short post
  for (let j = 0; j < FACINGS.length; j++) {
    for (const [k, head] of ['street_light_head', 'street_light_head_double', 'street_light_lamp'].entries()) {
      const x = 40 + 6 * k, z = 10 + 8 * j;
      const height = head === 'street_light_lamp' ? 2 : 4;
      await server.command(`fill ${x} ${Y} ${z} ${x} ${Y + height - 1} ${z} citybuilder:street_light_pole`);
      await set(x, Y + height, z, `citybuilder:${head}[facing=${FACINGS[j]}]`);
    }
  }
  // A two-block fixture stands as two halves; its upper half taken, the lower goes with it
  const r = await server.command(`fill 0 ${Y + 1} 16 20 ${Y + 1} 28 minecraft:air replace citybuilder:vending_red[half=upper]`);
  expect(JSON.stringify(r)).toMatch(/filled 4 block/);
  const lower = await server.command(`fill 0 ${Y} 16 20 ${Y} 28 minecraft:air replace citybuilder:vending_red`);
  expect(JSON.stringify(lower)).not.toMatch(/Successfully filled/);
  for (let j = 0; j < FACINGS.length; j++) {
    await set(0, Y, 16 + 4 * j, `citybuilder:vending_red[facing=${FACINGS[j]},half=lower]`);
    await set(0, Y + 1, 16 + 4 * j, `citybuilder:vending_red[facing=${FACINGS[j]},half=upper]`);
  }
  await view('fixtures-ac-front', 8, Y + 1.5, -6, 0, 12, testInfo);
  await view('fixtures-ac-close', 3, Y + 1.2, -2.5, 0, 10, testInfo);
  await view('fixtures-ac-side', -3, Y + 1.3, -1, -110, 15, testInfo);
  await view('fixtures-ac-above', 8, Y + 8, 18, 180, 40, testInfo);
  await view('fixtures-vending', 5, Y + 1.5, 12.5, 0, 10, testInfo);
  await view('fixtures-small', 9, Y + 1.6, 29.5, 0, 25, testInfo);
  await view('fixtures-small-above', 10, Y + 7, 50, 180, 40, testInfo);
  await view('fixtures-louvre', 33, Y + 0.5, 1, 0, -60, testInfo);
  await view('fixtures-street-lights', 46, Y + 3, -2, 0, 15, testInfo);
  await server.command('time set 18000');
  await view('fixtures-night', 5, Y + 1.5, 12.5, 0, 10, testInfo);
  await view('fixtures-street-lights-night', 46, Y + 6, 0, 0, 30, testInfo);
});
