// The in-game city map (key K, or /citymap): the test client opens it with the command over seed 12345's castle town,
// waits for the server to draw the tiles, and takes pictures zoomed out (districts) and in (lots and buildings), then
// inspects a spot. Kept in test-results/screens. Skipped without the test client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const TOWN = { x: -10232, z: -10272 };
const SCREENS = join(process.cwd(), 'test-results', 'screens');

/** Waits until every tile on screen has arrived (complete, if they become so within the time) and none is in flight. */
async function drawn(seconds) {
  let steady = 0;
  let state = null;
  for (let i = 0; i < seconds && steady < 4; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    state = await client.map();
    steady = state.inFlight === 0 && state.tiles > 0 && state.received === state.tiles && state.partial === 0
      ? steady + 1 : 0;
  }
  return state;
}

async function shot(name, testInfo) {
  // Still the player sent to the town (another run's client could have taken over the endpoint file)
  const s = await client.state();
  expect(Math.hypot(s.x - TOWN.x, s.z - TOWN.z)).toBeLessThan(1000);
  const file = join(SCREENS, `${name}.png`);
  await client.screenshot(file);
  expect(statSync(file).size).toBeGreaterThan(10000);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('the city map shows the town round the player', async ({}, testInfo) => {
  test.setTimeout(900_000);
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  // The town planned before the player goes there (this plans it now if the planner thread has not yet; a chunk of an
  // unplanned town would hold the server thread past the watchdog), the roads round it, the chunks under the player
  await server.planAround(TOWN.x, TOWN.z);
  const street = await server.street(TOWN.x, TOWN.z, 'ARTERIAL');
  expect(street.found).toBe(true);
  await server.generate(TOWN.x - 64, TOWN.z - 64, TOWN.x + 63, TOWN.z + 63);
  await server.command(`tp @a ${TOWN.x} 150 ${TOWN.z} 0 90`);

  // Opened by the command, through the server's packet
  await server.command('execute as @a run citymap');
  await waitForClient((s) => s.screen === 'CityMapScreen', 30, 'the map open');

  // Zoomed out: 4 blocks a pixel, the districts
  await client.map({ x: TOWN.x, z: TOWN.z, zoom: 2 });
  let map = await drawn(300);
  console.log('map at 4 blocks/px:', JSON.stringify(map));
  expect(map.received).toBeGreaterThan(0);
  await shot('citymap-districts', testInfo);

  // Zoomed in: a block a pixel, lots and buildings
  await client.map({ x: street.x, z: street.z, zoom: 0 });
  map = await drawn(300);
  console.log('map at 1 block/px:', JSON.stringify(map));
  expect(map.received).toBeGreaterThan(0);
  await shot('citymap-lots', testInfo);

  // A spot inspected: its town and district
  await client.map({ px: street.x, pz: street.z });
  let probe = [];
  for (let i = 0; i < 60 && !probe.some((l) => l.startsWith('town:')); i++) {
    await new Promise((r) => setTimeout(r, 1000));
    probe = (await client.map()).probe;
  }
  console.log('probe:', probe.join(' | '));
  expect(probe.some((l) => l.startsWith('town:'))).toBe(true);
  expect(probe.some((l) => l.startsWith('street:'))).toBe(true);
  await shot('citymap-probe', testInfo);
  await client.map({ close: 1 });
});
