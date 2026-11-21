// An embankment along a river through seed 12345's castle town, as Japanese rivers have them: from the water the
// terrace and promenade, the stone revetment, the bank road on the crest, the grassed back slope and the road along
// its foot. Checks the revetment is built; with the test client, pictures of it.
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

test('an embankment stands between the river and the town', async ({}, testInfo) => {
  test.setTimeout(900_000);
  await server.planAround(TOWN.x, TOWN.z);
  const e = await server.embankment(TOWN.x, TOWN.z);
  console.log('embankment', JSON.stringify(e));
  test.skip(!e.found, 'no embankment in the town');
  const x = Math.round(e.x);
  const z = Math.round(e.z);
  await server.generate(x - 48, z - 48, x + 47, z + 47);
  const blocks = await server.blocks(x - 24, z - 24, x + 23, z + 23);
  console.log('top', JSON.stringify(Object.entries(blocks.top).sort((a, b) => b[1] - a[1]).slice(0, 15)));
  console.log('paved', JSON.stringify(blocks.pavedHeights));
  if (clientRunning()) {
    if (!(await client.state()).inWorld) {
      await client.join();
      await waitForClient((st) => st.inWorld, 180, 'in the world');
    }
    await server.command('gamemode spectator @a');
    await server.command('time set noon');
    await server.command('gamerule doDaylightCycle false');
    await server.command('weather clear');
    // Looking along the river from over the water, and across the embankment from its land side
    const h = (e.heading * Math.PI) / 180;
    const along = (Math.atan2(-Math.cos(h), Math.sin(h)) * 180) / Math.PI;
    const shots = [
      ['embankment-along', e.x - e.nx * 30 - Math.cos(h) * 40, e.crest + 8, e.z - e.nz * 30 - Math.sin(h) * 40, along, 15],
      ['embankment-across', e.x + e.nx * (e.land + 25), e.crest + 6, e.z + e.nz * (e.land + 25),
        (Math.atan2(e.nx, -e.nz) * 180) / Math.PI, 15],
      ['embankment-above', e.x, e.crest + 60, e.z, 0, 90],
    ];
    for (const [name, sx, sy, sz, yaw, pitch] of shots) {
      await server.command(`tp @a ${sx.toFixed(1)} ${sy.toFixed(1)} ${sz.toFixed(1)} ${yaw.toFixed(1)} ${pitch}`);
      await client.look(yaw, pitch);
      await settle();
      const file = join(SCREENS, `${name}.png`);
      await client.screenshot(file);
      await testInfo.attach(name, { path: file, contentType: 'image/png' });
    }
  }
  expect(blocks.top['minecraft:stone_bricks'] ?? 0).toBeGreaterThan(20);
});
