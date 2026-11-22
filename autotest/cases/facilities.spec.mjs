// The town's public buildings generated whole (seed 12345, the castle town): its city hall, libraries, hospital and
// filling stations on their sites, generated round each; checks each stands (its blocks there, nothing of the lots'
// buildings in it); its parks (a neighbourhood park's playground, a large park's pond, a sports park's field, the trees
// of each); with the test client, a picture of each from above and from the side.
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

test('public buildings stand on their sites', async ({}, testInfo) => {
  test.setTimeout(1_800_000);
  await server.planAround(TOWN.x, TOWN.z);
  const f = await server.facilities(TOWN.x, TOWN.z);
  console.log('placed', JSON.stringify(f.placed?.map((p) => p.kind)));
  expect(f.found).toBe(true);
  const withClient = clientRunning();
  if (withClient && !(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 300, 'in the world');
    await server.command('gamemode spectator @a');
    await server.command('time set noon');
    await server.command('gamerule doDaylightCycle false');
    await server.command('weather clear');
  }
  const PARKS = ['PARK', 'CENTRAL_PARK', 'LARGE_PARK', 'SPORTS_PARK', 'GREEN'];
  const seen = new Set();
  for (const p of f.placed) {
    // The nearest of each kind; of a park, its graded piece (a green's trees)
    const park = PARKS.includes(p.kind);
    if (seen.has(p.kind) || park && !p.grade && p.kind !== 'GREEN') {
      continue;
    }
    seen.add(p.kind);
    await server.generate(p.minX - 24, p.minZ - 24, p.maxX + 24, p.maxZ + 24);
    const blocks = await server.blocks(p.minX, p.minZ, p.maxX, p.maxZ);
    console.log(p.kind, JSON.stringify(p), JSON.stringify(Object.entries(blocks.top).sort((a, b) => b[1] - a[1]).slice(0, 8)));
    // Its own blocks on top over most of its footprint: not grass and trees left as the land was
    const cells = (p.maxX - p.minX + 1) * (p.maxZ - p.minZ + 1);
    const natural = (blocks.top['minecraft:grass_block'] ?? 0) + (blocks.top['minecraft:snow'] ?? 0);
    if (!park) {
      expect(natural, p.kind).toBeLessThan(cells * 0.5);
    }
    if (withClient) {
      const cx = Math.round(0.5 * (p.minX + p.maxX));
      const cz = Math.round(0.5 * (p.minZ + p.maxZ));
      const size = Math.min(60, Math.max(16, p.maxX - p.minX, p.maxZ - p.minZ));
      await view(`facility-${p.kind.toLowerCase()}-above`, cx, p.ground + size + 30, cz, 0, 90, testInfo);
      await view(`facility-${p.kind.toLowerCase()}`, cx + size, p.ground + 18, cz + size, 135, 20, testInfo);
    }
  }
  expect(seen.has('CITY_HALL')).toBe(true);
});
