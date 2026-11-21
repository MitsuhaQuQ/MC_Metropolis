// Pictures of a place in seed 12345's world for a person to look at (a tool, not a check): the old road's bridge
// south of the castle town (PROBE_X, PROBE_Z, else -10240 -10688) from above and from the side, and where an arterial
// and a street end near the town's middle (a T or a dead end) from above. Kept in test-results/screens. Skipped
// without the test client (node run.mjs --client).
import { test } from '@playwright/test';
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

test('pictures of a place', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(900_000);
  if (!(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  const x = Number(process.env.PROBE_X ?? -10240);
  const z = Number(process.env.PROBE_Z ?? -10688);
  await server.planAround(x, z);
  await server.generate(x - 64, z - 64, x + 63, z + 63);
  await view('probe-above', x, 130, z, 0, 90, testInfo);
  await view('probe-side', x + 50, 95, z + 50, 135, 25, testInfo);
  for (const c of ['ARTERIAL', 'STREET']) {
    const e = await server.street(TOWN.x, TOWN.z, c, true);
    console.log('end', c, JSON.stringify(e));
    if (!e.found) {
      continue;
    }
    const ex = Math.round(e.x);
    const ez = Math.round(e.z);
    await server.generate(ex - 48, ez - 48, ex + 47, ez + 47);
    await view(`probe-end-${c.toLowerCase()}`, ex, 105, ez, 0, 90, testInfo);
  }
});
