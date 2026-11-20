// The landmark blocks in the game: pieces of the generated athletics ground (a bend of the track with its lane lines,
// the pitch's corner) and of the football stadium's main stand (seats, roof) are set block by block from the models
// that `gradlew :core:templeModels` writes (core/build/landmarks/models, see tools/assets/plateau_voxel.py), high in
// the air over spawn; the test client looks at them. Every state must be accepted by the game (the server's setblock
// fails on an unknown block or property). Skipped without the models or without the test client.
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { ROOT, server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const MODELS = join(ROOT, 'core', 'build', 'landmarks', 'models');
const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 250; // above the hills round spawn (at 160 they were buried in a mountain)

/** Sets the blocks of a model inside [x0, x1) × [z0, z1) (model coordinates), moved by (dx, dz) and up to Y. */
async function place(name, x0, z0, x1, z1, dx, dz) {
  const model = JSON.parse(readFileSync(join(MODELS, `${name}.json`), 'utf8'));
  let n = 0;
  for (const [x, y, z, state] of model.blocks) {
    if (x < x0 || x >= x1 || z < z0 || z >= z1) continue;
    const r = await server.command(`setblock ${x + dx} ${Y + y} ${z + dz} ${state}`);
    expect(JSON.stringify(r), `${state}`).not.toMatch(/Unknown|Expected|Invalid|Incorrect/);
    n++;
  }
  return n;
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

test('landmark blocks in the game', async ({}, testInfo) => {
  test.skip(!existsSync(join(MODELS, 'athletics_ground.json')), 'no landmark models (gradlew :core:templeModels)');
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(900_000);
  await server.generate(-48, -48, 95, 63);
  // The track's north-east bend and the finish line: the model's middle is at (109, 69).
  const track = await place('athletics_ground', 139, 17, 181, 45, -139, -17);
  // A slice of the football stadium's main stand, roof and all: the model's middle is at (96, 77).
  const stand = await place('stadium_football', 84, 2, 108, 40, -84 + 56, -2);
  expect(track).toBeGreaterThan(800);
  expect(stand).toBeGreaterThan(1500);
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  await view('landmark-track-above', 20, Y + 30, 14, 0, 90, testInfo);
  await view('landmark-track-low', 4, Y + 4, 32, 225, 22, testInfo);
  await view('landmark-stand', 68, Y + 8, 46, 180, 18, testInfo);
  await view('landmark-seats', 66, Y + 9, 30, 180, 30, testInfo);
});
