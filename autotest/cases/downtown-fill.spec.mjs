// Downtown lots built on (layout 11): in the metropolis nearest the castle town (seed 12345) the lots of the
// centre, the old town and the merged parcels within 160 m of the core, sampled every 6 blocks, each lot once: how
// many stand without a building and why, and the back lots' buildings standing in the world on their own land
// behind the lots in front. Pictures from above. Run with AUTOTEST_LAYOUT=10 for the layout before.
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { mkdirSync, writeFileSync } from 'node:fs';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const CORE = { x: -35834, z: -1645 };
const R = 160;
const SCREENS = join(process.cwd(), 'test-results', 'screens');
const OUT = join(process.cwd(), '..', 'build', 'debug', 'downtown-fill');
const LAYOUT = process.env.AUTOTEST_LAYOUT ?? '11';

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

test('downtown lots are built on', async ({}, testInfo) => {
  test.setTimeout(3_600_000);
  mkdirSync(OUT, { recursive: true });
  await server.planAround(CORE.x, CORE.z);
  await server.generate(CORE.x - R - 32, CORE.z - R - 32, CORE.x + R + 31, CORE.z + R + 31, 1800);
  const lots = new Map();
  for (let dx = -R; dx <= R; dx += 6) {
    for (let dz = -R; dz <= R; dz += 6) {
      if (dx * dx + dz * dz > R * R) continue;
      const x = CORE.x + dx + 0.5;
      const z = CORE.z + dz + 0.5;
      const p = await server.plot(x, z);
      if (!p.found || !p.lotId || p.plot === 'interior' || p.plot === 'remnant') continue;
      const downtown = p.use === 'PARCEL' || ['CENTRAL', 'STATION_FRONT', 'OLD_TOWN'].includes(p.district);
      if (!downtown || p.use === 'GARDEN' || p.use === 'PARKING' || p.use === 'FIELD' || p.use === 'STATION') continue;
      const lot = lots.get(p.lotId) ?? { ...p, samples: [] };
      lot.samples.push({ x, z, covered: p.covered });
      lots.set(p.lotId, lot);
    }
  }
  const reasons = {};
  let empty = 0;
  for (const lot of lots.values()) {
    if (lot.type) continue;
    empty++;
    const key = `${lot.plot}${lot.use === 'PARCEL' ? ' parcel' : ''}: ${lot.empty}`;
    reasons[key] = (reasons[key] ?? 0) + 1;
  }
  // The back lots' buildings stand in the world: a wall or floor a storey up at a sampled point they cover
  const backs = [...lots.values()].filter((l) => l.plot === 'back' && l.type).slice(0, 12);
  const standing = [];
  for (const lot of backs) {
    const at = lot.samples.find((s) => s.covered);
    if (!at) continue;
    // Above the ground floor's floor: a yard there is all air
    const col = await server.columnProfile(Math.floor(at.x), Math.floor(at.z), lot.floorY + 1, lot.floorY + 9);
    expect(col.loaded, JSON.stringify(at)).toBe(true);
    const solid = col.blocks.filter((b) => b.id !== 'minecraft:air').length;
    standing.push({ lot: lot.lotId, x: at.x, z: at.z, type: lot.type, storeys: lot.storeys, front: lot.front, solid });
  }
  const report = { layout: LAYOUT, lots: lots.size, empty, reasons, standing };
  console.log(JSON.stringify(report, null, 1));
  writeFileSync(join(OUT, `layout-${LAYOUT}.json`), JSON.stringify(report, null, 1));
  if (LAYOUT === '11') {
    // Left empty only under a public building or a park, by a railway, or a sliver no building fits
    for (const key of Object.keys(reasons)) {
      expect(key, JSON.stringify(reasons)).not.toMatch(/vacant/);
    }
    expect(empty / lots.size, JSON.stringify(reasons)).toBeLessThan(0.15);
    expect(standing.length).toBeGreaterThan(0);
    for (const s of standing) expect(s.solid, JSON.stringify(s)).toBeGreaterThan(0);
  }

  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  if (!(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 300, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  for (const [name, x, y, z, yaw, pitch] of [
    ['above', CORE.x, 230, CORE.z, 0, 90],
    ['oblique', CORE.x - 150, 150, CORE.z - 150, -45, 35],
  ]) {
    await server.command(`tp @a ${x} ${y} ${z} ${yaw} ${pitch}`);
    await client.look(yaw, pitch);
    await settle();
    const file = join(SCREENS, `downtown-${name}-v${LAYOUT}.png`);
    await client.screenshot(file);
    await testInfo.attach(name, { path: file, contentType: 'image/png' });
  }
});
