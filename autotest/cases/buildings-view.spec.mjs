// What the buildings look like: the test client flies over and walks the castle town (seed 12345) and takes pictures,
// kept in test-results/screens. Skipped without the test client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { statSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const TOWN = { x: -10232, z: -10272 };
const SCREENS = join(process.cwd(), 'test-results', 'screens');

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

/** Minecraft's yaw (0 faces +z) for a heading in degrees from +x towards +z. */
function yawOf(heading) {
  const h = (heading * Math.PI) / 180;
  return (Math.atan2(-Math.cos(h), Math.sin(h)) * 180) / Math.PI;
}

test('the buildings seen from the test client', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  await server.planAround(TOWN.x, TOWN.z);
  await server.generate(TOWN.x - 160, TOWN.z - 160, TOWN.x + 159, TOWN.z + 159);
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set 3000');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  await view('bld-oblique', TOWN.x + 90, 115, TOWN.z + 90, 135, 30, testInfo);
  await view('bld-low', TOWN.x - 70, 95, TOWN.z + 60, -135, 18, testInfo);
  // At eye level in a street and an arterial, looking along them
  for (const [name, cls] of [['bld-street', 'STREET'], ['bld-arterial', 'ARTERIAL'], ['bld-old', 'OLD_ROAD']]) {
    const s = await server.street(TOWN.x, TOWN.z, cls);
    console.log(name, JSON.stringify(s));
    if (!s.found) {
      continue;
    }
    const yaw = yawOf(s.heading);
    const back = 25;
    const h = (s.heading * Math.PI) / 180;
    const x = Math.round(s.x - Math.cos(h) * back);
    const z = Math.round(s.z - Math.sin(h) * back);
    await server.generate(x - 48, z - 48, x + 47, z + 47);
    const ground = s.ground ?? 70;
    await view(name, x, Math.round(ground) + 3, z, yaw, 5, testInfo);
    if (cls === 'ARTERIAL') {
      for (const side of [1, -1]) {
        // From the road's middle, looking across at one side's fronts
        await view(`bld-front-${side > 0 ? 'left' : 'right'}`, Math.round(s.x), Math.round(ground) + 4, Math.round(s.z),
          yawOf(s.heading + 55 * side), 8, testInfo);
      }
    }
  }
  // A stop line before a junction of arterials: on the incoming driver's left, seen from above and from the lane
  const stop = await server.stopLine(TOWN.x, TOWN.z);
  console.log('stop', JSON.stringify(stop));
  if (stop.found) {
    const h = (stop.heading * Math.PI) / 180;
    const at = (back) => [Math.round(stop.x - Math.cos(h) * back), Math.round(stop.z - Math.sin(h) * back)];
    const [sx, sz] = at(0);
    await server.generate(sx - 48, sz - 48, sx + 47, sz + 47);
    const again = await server.stopLine(TOWN.x, TOWN.z);
    const ground = Math.round(again.ground ?? 70);
    const [ax, az] = at(6);
    await view('road-stop-above', ax, ground + 22, az, yawOf(stop.heading), 65, testInfo);
    // From the left lane 14 back, at a driver's eye height, looking on into the junction
    const [lx, lz] = at(14);
    const left = h - Math.PI / 2;
    await view('road-stop-lane', Math.round(lx + Math.cos(left) * 2), ground + 2, Math.round(lz + Math.sin(left) * 2),
      yawOf(stop.heading), 12, testInfo);
  }
});
