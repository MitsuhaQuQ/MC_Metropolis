// Layout 12's fixes looked at in the user's world (seed 1568509124983131767, the spawn city round (5632, -3392)):
// the mezzanine station at (5909, -3102) reached by stairs out of its concourse's ends, not hung outside the viaduct;
// the library at (5666, -3370) level with its street; the castle's turrets standing on the ground. Pictures with the
// test client. Run with AUTOTEST_SEED=1568509124983131767 (and AUTOTEST_LAYOUT=11 for the layout before).
import { test, expect } from '@playwright/test';
import { join } from 'node:path';
import { server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const SCREENS = join(process.cwd(), 'test-results', 'screens');
const LAYOUT = process.env.AUTOTEST_LAYOUT ?? '12';
const STATION = { x: 5465, z: -3186 };
// (the stations are listed by the server; STATION only marks where the user saw one)
const LIBRARY = { x: 5666, z: -3370 };
const CITY = { x: 5632, z: -3392 };

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
  const file = join(SCREENS, `${name}-v${LAYOUT}.png`);
  await client.screenshot(file);
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

test('the user world fixes', async ({}, testInfo) => {
  test.setTimeout(3_600_000);
  await server.planAround(CITY.x, CITY.z);
  await server.generate(STATION.x - 96, STATION.z - 96, STATION.x + 95, STATION.z + 95, 1800);
  await server.generate(LIBRARY.x - 64, LIBRARY.z - 64, LIBRARY.x + 63, LIBRARY.z + 63, 1800);
  const station = await server.rail(STATION.x, STATION.z, 'station');
  console.log('station', JSON.stringify(station));
  const castle = await server.castle(CITY.x, CITY.z);
  console.log('castle', JSON.stringify(castle).slice(0, 400));
  // The library's way in, as built
  const f = await server.facilities(LIBRARY.x, LIBRARY.z);
  const library = f.placed.filter((p) => p.kind === 'LIBRARY' && p.grade)
    .sort((a, b) => Math.hypot(a.minX - LIBRARY.x, a.minZ - LIBRARY.z) - Math.hypot(b.minX - LIBRARY.x, b.minZ - LIBRARY.z))[0];
  if (library) {
    const steps = await server.facilitySteps(LIBRARY.x, LIBRARY.z, library.id);
    console.log('library way in', JSON.stringify(library).slice(0, 200), 'largest step', steps.largest);
    if (LAYOUT === '12' && steps.pairs.length > 0) expect(steps.largest).toBeLessThanOrEqual(0.5 + 1e-6);
  }
  // The castle's turrets: their model's lowest walls a block or two over their ground, not on a stone base
  const turrets = f.placed.filter((p) => p.kind === 'CASTLE_PARK' && p.castlePart && p.castlePart !== 'GATE'
    && !String(p.castlePart).includes('KEEP'));
  console.log('castle parts', JSON.stringify(turrets.map((p) => [p.castlePart, p.minX, p.minZ, p.ground, p.modelHeight])));
  if (LAYOUT === '12') {
    for (const t of turrets) expect(t.modelHeight, JSON.stringify(t)).toBeLessThan(15);
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
  // From the ground beside the line (heading 90: along +x) and along it from beyond each end, under the deck
  // The nearest mezzanine station with ways out of its own, as this world built it
  const list = await server.stations(CITY.x, CITY.z);
  console.log('stations', JSON.stringify(list.stations?.map((s) => [Math.round(s.x), Math.round(s.z), s.mezzanine, s.mezzanineExits])));
  const mz = list.stations.find((s) => s.mezzanine && (LAYOUT !== '12' || s.mezzanineExits)) ?? list.stations[0];
  const sx = Math.round(mz.x);
  const sz = Math.round(mz.z);
  await server.generate(sx - 64, sz - 64, sx + 63, sz + 63, 1800);
  const gy = Math.round(mz.level) - 15;
  // Round the station at a distance, a little above the street, each looking at its middle
  await view('user-station-top', sx, gy + 70, sz, 0, 90, testInfo);
  // Low, under the deck's edge on each side, looking along the line and in at the concourse
  for (const [name, dx, dz] of [['low-n', 0, 22], ['low-s', 0, -22], ['low-e', 22, 0], ['low-w', -22, 0]]) {
    const x = sx + dx, z = sz + dz;
    const yaw = (Math.atan2(-(sx - x), sz - z) * 180) / Math.PI;
    await view(`user-station-${name}`, x, gy + 4, z, yaw, -5, testInfo);
  }
  for (const [name, dx, dz] of [['ne', 1, 1], ['nw', -1, 1], ['se', 1, -1], ['sw', -1, -1]]) {
    const x = sx + dx * 30, z = sz + dz * 30;
    const yaw = (Math.atan2(-(sx - x), sz - z) * 180) / Math.PI;
    await view(`user-station-${name}`, x, gy + 32, z, yaw, 35, testInfo);
  }
  if (library) {
    const cx = Math.round(0.5 * (library.minX + library.maxX));
    const cz = Math.round(0.5 * (library.minZ + library.maxZ));
    await view('user-library', cx + 26, library.ground + 10, cz + 26, 135, 25, testInfo);
  }
  for (const [i, t] of turrets.slice(0, 2).entries()) {
    await server.generate(t.minX - 32, t.minZ - 32, t.maxX + 32, t.maxZ + 32);
    const cx = Math.round(0.5 * (t.minX + t.maxX));
    const cz = Math.round(0.5 * (t.minZ + t.maxZ));
    await view(`user-turret-${i}`, cx + 30, t.ground + 18, cz + 30, 135, 25, testInfo);
  }
});
