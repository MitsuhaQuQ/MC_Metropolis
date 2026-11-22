// A town's edge thinning into its fields (seed 12345, the castle town): high pictures looking down over the built
// land's edge out into the farmland, in four directions from the town's middle.
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

test('the town thins into its fields', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.setTimeout(1_800_000);
  await server.planAround(TOWN.x, TOWN.z);
  if (!(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 300, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  for (const [name, dx, dz, yaw] of [['east', 1, 0, -90], ['south', 0, 1, 0]]) {
    // Walk out from the middle until the plot lookup finds no town
    let r = 600;
    for (; r < 4000; r += 150) {
      const p = await server.plot(TOWN.x + dx * r, TOWN.z + dz * r);
      if (!p.found) break;
    }
    const x = TOWN.x + dx * (r - 150);
    const z = TOWN.z + dz * (r - 150);
    console.log('edge', name, r, x, z);
    await server.generate(x - 160, z - 160, x + 159, z + 159);
    await server.command(`tp @a ${x - dx * 60} 170 ${z - dz * 60} ${yaw} 40`);
    await client.look(yaw, 40);
    await settle();
    const file = join(SCREENS, `fringe-${name}.png`);
    await client.screenshot(file);
    await testInfo.attach(name, { path: file, contentType: 'image/png' });
  }
});
