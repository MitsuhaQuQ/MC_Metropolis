// Thin walls in the game: a little room of them, its walls straight runs and its corners outer corners, an inner
// corner's post, and a row of every material, set high over spawn with setblock (every state must be accepted); the
// test client looks at them from above (the walls' thickness shows along their tops) and from inside. Skipped without
// the test client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250;
const MATERIALS = ['andesite', 'birch_planks', 'black_concrete', 'bricks', 'brown_terracotta', 'calcite',
  'cracked_deepslate_tiles', 'cracked_stone_bricks', 'cut_sandstone', 'cyan_terracotta', 'dark_oak_planks',
  'deepslate_tiles', 'diorite', 'gray_concrete', 'gray_terracotta', 'green_terracotta', 'iron_block',
  'light_blue_terracotta', 'light_gray_concrete', 'light_gray_terracotta', 'mangrove_planks', 'mossy_stone_bricks',
  'mud_bricks', 'orange_terracotta', 'packed_mud', 'pink_terracotta', 'polished_andesite', 'polished_blackstone',
  'polished_deepslate', 'polished_diorite', 'sandstone', 'smooth_quartz', 'smooth_sandstone', 'spruce_planks', 'stone',
  'stone_bricks', 'stripped_dark_oak_log', 'stripped_spruce_log', 'terracotta', 'white_concrete', 'white_terracotta',
  'yellow_terracotta'];

async function set(x, y, z, state) {
  const r = await server.command(`setblock ${x} ${y} ${z} ${state}`);
  expect(JSON.stringify(r), state).not.toMatch(/Unknown|Expected|Invalid|Incorrect/);
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

test('thin walls in the game', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(600_000);
  await server.generate(-48, -48, 47, 47);
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set 6000');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  // A floor, and a room 7 by 5 on it, three high: the walls against the outside, the corners outer corners
  await server.command(`fill -2 ${Y - 1} -2 8 ${Y - 1} 6 minecraft:smooth_stone`);
  const wall = 'citybuilder:thin_white_concrete';
  for (let y = Y; y < Y + 3; y++) {
    for (let x = 0; x <= 6; x++) {
      for (let z = 0; z <= 4; z++) {
        const n = z === 0, s = z === 4, w = x === 0, e = x === 6;
        let facing = null, shape = 'straight';
        if (n && w) [facing, shape] = ['west', 'outer'];
        else if (n && e) [facing, shape] = ['north', 'outer'];
        else if (s && e) [facing, shape] = ['east', 'outer'];
        else if (s && w) [facing, shape] = ['south', 'outer'];
        else if (n) facing = 'north';
        else if (s) facing = 'south';
        else if (w) facing = 'west';
        else if (e) facing = 'east';
        if (facing) await set(x, y, z, `${wall}[facing=${facing},shape=${shape}]`);
      }
    }
  }
  // An inner corner beside it: runs against east (to the north) and north (to the east) closed by the post between
  await set(10, Y, 1, `${wall}[facing=east,shape=straight]`);
  await set(11, Y, 2, `${wall}[facing=north,shape=straight]`);
  await set(10, Y, 2, `${wall}[facing=north,shape=inner]`);
  // Every material, a straight run facing north
  for (let i = 0; i < MATERIALS.length; i++) {
    await set(-20 + i, Y, -6, `citybuilder:thin_${MATERIALS[i]}[facing=north,shape=straight]`);
  }
  await view('thin-above', 3, Y + 9, 10, 180, 55, testInfo);
  await view('thin-inside', 3, Y + 1, 2, -135, 10, testInfo);
  await view('thin-materials', 0, Y + 3, -32, 0, 8, testInfo);
});
