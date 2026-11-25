// The user's 0.0.12 world (seed -3822740797505900337, its MTM_worldgen settings from level.dat in the dev server's
// config/mtm_worldgen.json): the spots of the user's screenshots round the lake city, seen again with the layout this
// build writes (23: no long bridges to small islands, stations over water on bents). Pictures with the test client.
// Run with AUTOTEST_SEED=-3822740797505900337 node run.mjs --client cases/user-world-l23.spec.mjs
import { test } from '@playwright/test';
import { join } from 'node:path';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const LAYOUT = process.env.AUTOTEST_LAYOUT ?? '23';
const CITY = { x: 6464, z: -4864 };
// Where the user stood (x, y, z): each seen from straight above and from the south looking north
const SPOTS = [
  { name: 'slope-street', x: 6537, y: 176, z: -4254 },
  { name: 'main-station', x: 6675, y: 221, z: -4333 },
  { name: 'park-river', x: 6376, y: 308, z: -3880 },
  { name: 'island', x: 6520, y: 239, z: -4880 },
  { name: 'long-bridge', x: 6271, y: 179, z: -5234 },
  { name: 'road-viaducts', x: 6735, y: 186, z: -4584 },
];

async function settle() {
  let last = -1;
  let steady = 0;
  for (let i = 0; i < 120 && steady < 6; i++) {
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
  const file = join(SCREENS, `${name}-v${LAYOUT}.png`);
  await client.screenshot(file);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('the user lake city, layout 23', async ({}, testInfo) => {
  test.setTimeout(7_200_000);
  const planned = await server.planAround(CITY.x, CITY.z);
  console.log('planned', JSON.stringify(planned).slice(0, 300));
  for (const s of SPOTS) {
    const r = await server.generate(s.x - 160, s.z - 160, s.x + 159, s.z + 159, 3600);
    console.log(`generated ${s.name}`, JSON.stringify(r).slice(0, 200));
  }
  // The crossings layout 23 leaves out: the nearest bridge pieces of the old road and the arterial round the island
  for (const [cls, x, z] of [['OLD_ROAD', 6200, -4636], ['ARTERIAL', 6600, -4560]]) {
    const near = await server.street(x, z, cls, false, 'BRIDGE');
    console.log(`bridge ${cls} near ${x},${z}`, JSON.stringify(near).slice(0, 300));
  }
  const list = await server.stations(CITY.x, CITY.z);
  console.log('stations', JSON.stringify(list.stations?.filter((s) => s.main)));

  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  if (!(await client.state()).inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 300, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  for (const s of SPOTS) {
    await view(`l23-${s.name}-top`, s.x, s.y + 60, s.z, 0, 90, testInfo);
    // From 60 south and 30 over where the user stood, looking north and down at the spot
    await view(`l23-${s.name}-south`, s.x, s.y + 30, s.z + 60, 180, 30, testInfo);
  }
  // The main station's end out over the lake, from a little over the water to its east, looking west under it
  await view('l23-main-station-under', 6731, 160, -4296, 90, 5, testInfo);
});
