// This mod's wall finishes in the game (tools/textures/wall_textures.py, BuildingBlocks.WALLS): every material as a
// panel 2 wide and 3 high, fresh on the left and weathered on the right, in rows, set high over spawn with setblock
// (every block must be accepted), then a row of their thin walls with an outer corner each; the test client looks at
// the panels from in front, half at a time, and at the thin walls from above. Skipped without the test client
// (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { readFileSync, statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250;
const PER_ROW = 14;

/** The materials from BuildingBlocks.WALLS (name, vanilla block pairs) and THIN_FINISHES. */
function materials() {
  const java = readFileSync(join(process.cwd(), '..', 'common', 'src', 'main', 'java', 'com', 'mtm', 'citybuilder',
    'block', 'BuildingBlocks.java'), 'utf8');
  const walls = [...java.match(/String\[\] WALLS = \{([^}]*)\}/)[1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
  const names = walls.filter((_, i) => i % 2 === 0);
  const thin = [...java.match(/THIN_FINISHES = java\.util\.Set\.of\(([^)]*)\)/)[1].matchAll(/"([a-z_]+)"/g)]
    .map((m) => m[1]);
  return { fresh: names.filter((n) => !n.endsWith('_aged')), all: new Set(names), thin };
}

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

test('wall finishes in the game', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(900_000);
  const { fresh, all, thin } = materials();
  expect(fresh.length).toBeGreaterThan(50);
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
  // Panels facing south (toward +z), rows from the top down
  const rows = Math.ceil(fresh.length / PER_ROW);
  for (let i = 0; i < fresh.length; i++) {
    const x = -21 + 3 * (i % PER_ROW);
    const top = Y + 4 * (rows - 1 - Math.floor(i / PER_ROW));
    const aged = `${fresh[i]}_aged`;
    for (let dy = 0; dy < 3; dy++) {
      await set(x, top + dy, 0, `citybuilder:${fresh[i]}`);
      await set(x + 1, top + dy, 0, `citybuilder:${all.has(aged) ? aged : fresh[i]}`);
    }
  }
  // Thin walls: a run of each, facing south, two high, the end an outer corner turning back north
  for (let i = 0; i < thin.length; i++) {
    const x = -60 + i;
    for (let dy = 0; dy < 2; dy++) {
      await set(x, Y + dy, 20, `citybuilder:thin_${thin[i]}[facing=south,shape=straight]`);
    }
  }
  const middle = Y + 2 * rows;
  await view('walls-left', -12, middle, 26, 180, 0, testInfo);
  await view('walls-right', 9, middle, 26, 180, 0, testInfo);
  await view('walls-near', -16, Y + 1.5, 9, 180, 5, testInfo);
  await view('walls-thin', -30, Y + 8, 30, 180, 30, testInfo);
});
