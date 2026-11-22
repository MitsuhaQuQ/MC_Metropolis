// A town's street bridges in the game (seed 12345, the castle town): the nearest arterial and collector bridge to
// the town's middle, generated round its ends; the deck stands over its banks, the streets of both banks ramp up to it
// with masonry slopes under them where they stand two blocks or more over the land. Pictures from above and from the
// side of each end, in test-results/screens (bridge-*.png). Skipped without the test client (node run.mjs --client).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
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
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('town bridges and their ramps', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(1_200_000);
  if (!(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 300, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  await server.planAround(TOWN.x, TOWN.z);
  let seen = 0;
  for (const c of ['ARTERIAL', 'COLLECTOR']) {
    const b = await server.street(TOWN.x, TOWN.z, c, false, 'BRIDGE');
    console.log('bridge', c, JSON.stringify(b));
    if (!b.found) {
      continue;
    }
    seen++;
    const x = Math.round(b.x);
    const z = Math.round(b.z);
    await server.generate(x - 96, z - 96, x + 95, z + 95);
    const h = b.heading * Math.PI / 180;
    const ground = b.ground ?? 70;
    await view(`bridge-${c.toLowerCase()}-above`, x, ground + 70, z, 0, 90, testInfo);
    // From beside the bridge, back along it towards one bank, then the other
    for (const [k, s] of [[0, 1], [1, -1]]) {
      const ex = x + Math.cos(h) * 45 * s - Math.sin(h) * 30;
      const ez = z + Math.sin(h) * 45 * s + Math.cos(h) * 30;
      const look = (Math.atan2(-((x + Math.cos(h) * 70 * s) - ex), (z + Math.sin(h) * 70 * s) - ez) * 180) / Math.PI;
      await view(`bridge-${c.toLowerCase()}-bank${k}`, Math.round(ex), ground + 12, Math.round(ez), Math.round(look), 15, testInfo);
      // The approach street from beside it, square to it, looking at a point some way in from the bridge
      for (const reach of [70, 110]) {
        const tx = x + Math.cos(h) * reach * s;
        const tz = z + Math.sin(h) * reach * s;
        const cx = tx - Math.sin(h) * 28;
        const cz = tz + Math.cos(h) * 28;
        const yaw = (Math.atan2(-(tx - cx), tz - cz) * 180) / Math.PI;
        await server.generate(Math.round(tx) - 48, Math.round(tz) - 48, Math.round(tx) + 47, Math.round(tz) + 47);
        await view(`bridge-${c.toLowerCase()}-approach${k}-${reach}`, Math.round(cx), ground + 10, Math.round(cz),
          Math.round(yaw), 12, testInfo);
        await view(`bridge-${c.toLowerCase()}-top${k}-${reach}`, Math.round(tx), ground + 55, Math.round(tz), 0, 90, testInfo);
        console.log('ramp', c, k, reach, JSON.stringify(await server.blocks(Math.round(tx) - 2, Math.round(tz) - 2,
          Math.round(tx) + 2, Math.round(tz) + 2)));
      }
    }
  }
  expect(seen).toBeGreaterThan(0);
});
