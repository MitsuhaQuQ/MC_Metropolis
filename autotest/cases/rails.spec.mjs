// The railways built through seed 12345's castle town: its own lines' and the intercity lines' stretches above ground,
// nearest the town's middle of each kind (a viaduct, a stretch at grade, a station's platforms), generated round
// each; checks the ballast is laid; with the test client, pictures from above and from beside.
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

test('railways stand through the town', async ({}, testInfo) => {
  test.setTimeout(1_800_000);
  await server.planAround(TOWN.x, TOWN.z);
  const withClient = clientRunning();
  if (withClient && !(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 300, 'in the world');
    await server.command('gamemode spectator @a');
    await server.command('time set noon');
    await server.command('gamerule doDaylightCycle false');
    await server.command('weather clear');
  }
  let ballast = 0;
  for (const kind of ['elevated', 'ground', 'station', 'subway', 'wild']) {
    const p = await server.rail(TOWN.x, TOWN.z, kind);
    console.log(kind, JSON.stringify(p));
    if (!p.found) {
      continue;
    }
    const x = Math.round(p.x);
    const z = Math.round(p.z);
    await server.generate(x - 48, z - 48, x + 47, z + 47);
    const blocks = await server.blocks(x - 8, z - 8, x + 8, z + 8);
    ballast += blocks.top['minecraft:gravel'] ?? 0;
    if (kind === 'subway') {
      // The box under the street: its roof's concrete and the ballast in it
      const y = Math.floor(p.level);
      const r = await server.command(`execute if block ${x} ${y} ${z} minecraft:gravel`);
      const air = await server.command(`execute if block ${x} ${y + 3} ${z} minecraft:air`);
      console.log('subway bed', JSON.stringify(r), JSON.stringify(air));
    }
    console.log(kind, JSON.stringify(Object.entries(blocks.top).sort((a, b) => b[1] - a[1]).slice(0, 8)));
    if (withClient) {
      // Underground: the camera inside the box, along the line
      const y = Math.round(p.level ?? 75);
      const h = p.heading * Math.PI / 180;
      // From beside, looking square across the line
      const sx = x - Math.sin(h) * 26;
      const sz = z + Math.cos(h) * 26;
      const yaw = (Math.atan2(-(x - sx), z - sz) * 180) / Math.PI;
      if (kind === 'subway') {
        // Inside the box, looking along the line
        const along = (Math.atan2(-Math.cos(h), Math.sin(h)) * 180) / Math.PI;
        await view('rail-subway', x, Math.floor(p.level) + 2, z, Math.round(along), 0, testInfo);
      } else {
        await view(`rail-${kind}`, Math.round(sx), y + 6, Math.round(sz), Math.round(yaw), 15, testInfo);
        if (kind === 'elevated' || kind === 'station') {
          // From the ground beside it, obliquely along it: the piers, the spans, a station's concourse
          const g = Math.round(p.ground ?? (y - 9));
          const ox = x - Math.sin(h) * 22 - Math.cos(h) * 30;
          const oz = z + Math.cos(h) * 22 - Math.sin(h) * 30;
          const oyaw = (Math.atan2(-(x - ox), z - oz) * 180) / Math.PI;
          await view(`rail-${kind}-low`, Math.round(ox), g + 3, Math.round(oz), Math.round(oyaw), -8, testInfo);
        }
      }
      await view(`rail-${kind}-above`, x, y + 40, z, 0, 90, testInfo);
    }
  }
  expect(ballast).toBeGreaterThan(20);
});
