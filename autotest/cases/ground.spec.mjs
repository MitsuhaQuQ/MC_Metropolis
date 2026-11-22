// The graded ground's blocks in the game: each 45 degree slope (grass, earth, snow, ashlar) as a little two-step bank
// rising north and one rising east, and the grass, earth and snow layers 1 to 8 in a row, set high over spawn on grass with
// setblock (every state must be accepted); the test client looks at them from the side and from above. Pictures in
// test-results/screens (ground-*.png). Skipped without the test client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250;
const SLOPES = [['grass_slope', 'minecraft:grass_block'], ['dirt_slope', 'minecraft:dirt'], ['snow_slope', 'minecraft:snow_block'],
  ['stone_ashlar_slope', 'citybuilder:stone_ashlar']];

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

test('graded ground blocks in the game', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(600_000);
  await server.generate(-48, -48, 63, 63);
  if (!(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set 6000');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  await server.command(`fill -4 ${Y - 1} -4 40 ${Y + 4} 40 minecraft:air`);
  await server.command(`fill -4 ${Y - 1} -4 40 ${Y - 1} 40 minecraft:grass_block`);
  // Each slope: a bank two high at z 0, a slope on a block at z 1, a slope on the ground at z 2, rising north; and the
  // same turned to rise east, further south
  let x = 0;
  for (const [name, full] of SLOPES) {
    for (let w = 0; w < 3; w++) {
      await set(x + w, Y, 0, full);
      await set(x + w, Y + 1, 0, full);
      await set(x + w, Y, 1, full);
      await set(x + w, Y + 1, 1, `citybuilder:${name}[facing=north]`);
      await set(x + w, Y, 2, `citybuilder:${name}[facing=north]`);
      await set(x + 2, Y, 8 + w, full);
      await set(x + 2, Y + 1, 8 + w, full);
      await set(x + 1, Y, 8 + w, full);
      await set(x + 1, Y + 1, 8 + w, `citybuilder:${name}[facing=east]`);
      await set(x, Y, 8 + w, `citybuilder:${name}[facing=east]`);
    }
    x += 6;
  }
  // A little mound of each: a block with a straight slope on each side and an outer corner at each corner, and a
  // hollow's inner corners round a pit beside it
  x = 0;
  for (const [name, full] of SLOPES) {
    const cz = 30;
    await set(x + 1, Y, cz + 1, full);
    for (const [dx, dz, f] of [[1, 0, 'south'], [2, 1, 'west'], [1, 2, 'north'], [0, 1, 'east']]) {
      await set(x + dx, Y, cz + dz, `citybuilder:${name}[facing=${f},shape=straight]`);
    }
    for (const [dx, dz, f] of [[0, 0, 'east'], [2, 0, 'south'], [2, 2, 'west'], [0, 2, 'north']]) {
      await set(x + dx, Y, cz + dz, `citybuilder:${name}[facing=${f},shape=outer]`);
    }
    x += 6;
  }
  // The layers 1 to 8, grass in one row and earth in the next
  for (let n = 1; n <= 8; n++) {
    await set(n - 1, Y, 16, `citybuilder:grass_layer[layers=${n}]`);
    await set(n - 1, Y, 18, `citybuilder:dirt_layer[layers=${n}]`);
    await set(n - 1, Y, 20, `citybuilder:snow_layer[layers=${n}]`);
  }
  await view('ground-slopes-side', 30, Y + 2, 1, 90, 10, testInfo);
  await view('ground-slopes', 10, Y + 9, 14, 180, 40, testInfo);
  await view('ground-slopes-east', -8, Y + 4, 9, -90, 20, testInfo);
  await view('ground-layers', 4, Y + 4, 23, 180, 35, testInfo);
  await view('ground-corners', 10, Y + 6, 38, 180, 40, testInfo);
});
