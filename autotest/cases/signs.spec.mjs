// The shops' signs in the game: every state of each (facing, glyph) set with setblock somewhere out of sight (every
// state must be accepted), then a mock shop street over spawn: a wall with the fascia boards and lit fascias in rows
// across it and the projecting signs standing out from it, four blocks tall each. The test client looks at them from
// the front, along the wall (the blades' sides) and at night. Skipped without the test client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250;
const FACINGS = ['north', 'east', 'south', 'west'];
const BLADES = ['white', 'red', 'navy', 'green', 'yellow', 'black'].map((c) => `sign_blade_${c}`);
const FASCIAS = ['wood', 'white', 'navy', 'green', 'black'].map((c) => `fascia_sign_${c}`);
const LIT = ['red', 'blue', 'green', 'black'].map((c) => `fascia_lit_${c}`);

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

test('shop signs in the game', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(900_000);
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
  // Every state, one block at a time out of sight
  for (const name of [...BLADES, ...FASCIAS, ...LIT]) {
    for (const facing of FACINGS) {
      for (let g = 0; g <= 8; g++) {
        await set(40, Y - 20, 40, `citybuilder:${name}[facing=${facing},glyph=${g}]`);
      }
    }
  }
  await server.command(`setblock 40 ${Y - 20} 40 minecraft:air`);
  // The street: a plastered wall along x at z = 0, its front to the north; the pavement before it
  await server.command(`fill -4 ${Y - 1} -16 60 ${Y + 8} 4 minecraft:air`);
  await server.command(`fill -4 ${Y - 1} -16 60 ${Y - 1} 4 minecraft:smooth_stone`);
  await server.command(`fill -2 ${Y} 0 56 ${Y + 7} 0 citybuilder:plaster_white`);
  // Fascia boards a row over the shop fronts, six blocks of each with a plain panel at either end
  let x = 0;
  for (const name of FASCIAS) {
    for (let i = 0; i < 8; i++) {
      const g = i === 0 || i === 7 ? 0 : 1 + ((i * 3 + x) % 8);
      await set(x + i, Y + 3, 0, `citybuilder:${name}[facing=north,glyph=${g}]`);
    }
    x += 10;
  }
  // Lit fascias higher up
  x = 0;
  for (const name of LIT) {
    for (let i = 0; i < 8; i++) {
      const g = i === 0 || i === 7 ? 0 : 1 + ((i * 5 + x) % 8);
      await set(x + i, Y + 6, 0, `citybuilder:${name}[facing=north,glyph=${g}]`);
    }
    x += 10;
  }
  // Projecting signs a block out from the wall, four tall, from the first floor up
  x = 1;
  for (const name of BLADES) {
    for (let k = 0; k < 4; k++) {
      await set(x, Y + 3 + k, -1, `citybuilder:${name}[facing=north,glyph=${1 + ((k * 3 + x) % 8)}]`);
    }
    x += 9;
  }
  await view('signs-front', 26, Y + 4, -18, 0, 5, testInfo);
  await view('signs-along', -8, Y + 4, -6, -70, 0, testInfo);
  await view('signs-close', 4, Y + 4, -5, -20, 0, testInfo);
  await server.command('time set 18000');
  await view('signs-night', 26, Y + 4, -18, 0, 5, testInfo);
  await server.command('time set 6000');
});
