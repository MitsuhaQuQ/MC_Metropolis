// What a player sees of a town: the test client joins, flies over the castle town (seed 12345) and takes pictures,
// attached to the report and kept in test-results/screens for a person to look at. Skipped without the test client
// (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const TOWN = { x: -10232, z: -10272 };
const SCREENS = join(process.cwd(), 'test-results', 'screens');

/** Waits until the client has stopped compiling new chunk sections for a few seconds. */
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
  expect(statSync(file).size).toBeGreaterThan(10000);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('the castle town seen from the test client', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  // From above, looking down; then along a street, looking ahead
  await view('town-above', TOWN.x, 150, TOWN.z, 0, 90, testInfo);
  await view('town-oblique', TOWN.x, 100, TOWN.z + 120, 180, 35, testInfo);
  await view('town-street', TOWN.x + 20, 72, TOWN.z + 20, 135, 10, testInfo);
  // The boulevard nearest the centre, as this world planned it: from above, then along it
  const b = await server.street(TOWN.x, TOWN.z, 'BOULEVARD');
  expect(b.found).toBe(true);
  const bx = Math.round(b.x);
  const bz = Math.round(b.z);
  await server.generate(bx - 48, bz - 48, bx + 47, bz + 47);
  await view('boulevard-above', bx, 130, bz, 0, 90, testInfo);
  // Standing 40 back along it, a little to one side of the median, looking along it (Minecraft's yaw: 0 faces +z)
  const h = (b.heading * Math.PI) / 180;
  const yaw = (Math.atan2(-Math.cos(h), Math.sin(h)) * 180) / Math.PI;
  // Where a boulevard ends, at a junction
  const e = await server.street(TOWN.x, TOWN.z, 'BOULEVARD', true);
  expect(e.found).toBe(true);
  await server.generate(Math.round(e.x) - 48, Math.round(e.z) - 48, Math.round(e.x) + 47, Math.round(e.z) + 47);
  await view('boulevard-end', Math.round(e.x), 130, Math.round(e.z), 0, 90, testInfo);
  await view('boulevard-along', bx - 40 * Math.cos(h) - 6 * Math.sin(h), 74, bz - 40 * Math.sin(h) + 6 * Math.cos(h), yaw, 12, testInfo);
});
