// A look at windows the server finds unjoined (an arm into the air, or a side along their run not joined to the wall
// beside them): close-ups from either side of each of a few. A tool for finding faults, not a check.
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const TOWN = { x: -10232, z: -10272 };
const R = 64;
const SCREENS = join(process.cwd(), 'test-results', 'screens');
// Minecraft's 2D direction values: south, west, north, east
const DIRS = [[0, 1], [-1, 0], [0, -1], [1, 0]];

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

test('unjoined windows up close', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(900_000);
  await server.planAround(TOWN.x, TOWN.z);
  await server.generate(TOWN.x - R, TOWN.z - R, TOWN.x + R - 1, TOWN.z + R - 1);
  const blocks = await server.blocks(TOWN.x - R, TOWN.z - R, TOWN.x + R - 1, TOWN.z + R - 1);
  console.log('windows', JSON.stringify(blocks.windows));
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set 6000');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  const cases = [...blocks.windows.danglingAt.slice(0, 3).map((p) => ['dangling', p]),
    ...blocks.windows.unjoinedAt.slice(0, 3).map((p) => ['unjoined', p])];
  let n = 0;
  for (const [kind, [x, y, z, d]] of cases) {
    const [dx, dz] = DIRS[d];
    // From either side of the window's run, 3 out and a little up, looking at the side in question
    for (const side of [1, -1]) {
      const px = x + 0.5 + dx * 0.5 + -dz * side * 3.5;
      const pz = z + 0.5 + dz * 0.5 + dx * side * 3.5;
      const tx = x + 0.5 + dx * 0.5 - px;
      const tz = z + 0.5 + dz * 0.5 - pz;
      const yaw = (Math.atan2(-tx, tz) * 180) / Math.PI;
      await server.command(`tp @a ${px} ${y + 0.9} ${pz} ${yaw} 10`);
      await client.look(yaw, 10);
      await settle();
      const name = `win-${kind}-${n}-${side > 0 ? 'a' : 'b'}`;
      const file = join(SCREENS, `${name}.png`);
      await client.screenshot(file);
      expect(statSync(file).size).toBeGreaterThan(10000);
      await testInfo.attach(name, { path: file, contentType: 'image/png' });
    }
    n++;
  }
});
