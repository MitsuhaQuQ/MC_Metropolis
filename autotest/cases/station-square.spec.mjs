// The main station's square of the castle town (seed 12345), laid on the land the streets leave in front of the
// station: paved, and with the test client pictures from above, from over the avenue and from its island.
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

test('the station square on its own land', async ({}, testInfo) => {
  const st = await server.station(TOWN.x, TOWN.z);
  console.log('station', JSON.stringify(st));
  expect(st.found).toBe(true);
  const [sx, sz] = st.middle;
  const x1 = Math.round(sx) - 72;
  const z1 = Math.round(sz) - 72;
  await server.generate(x1, z1, x1 + 143, z1 + 143);
  const blocks = await server.blocks(x1, z1, x1 + 143, z1 + 143);
  console.log('square blocks', JSON.stringify(blocks).slice(0, 900));
  if (!clientRunning()) {
    return;
  }
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  const sg = await server.station(TOWN.x, TOWN.z);
  const ground = sg.ground ?? 70;
  await view('square-above', Math.round(sx), ground + 100, Math.round(sz), 0, 90, testInfo);
  const oh = (st.outward * Math.PI) / 180;
  const [mx, mz] = st.mouth;
  const back = (Math.atan2(Math.cos(oh), -Math.sin(oh)) * 180) / Math.PI;
  await view('square-throat', mx + 30 * Math.cos(oh), ground + 25, mz + 30 * Math.sin(oh), back, 30, testInfo);
  const [lx, lz] = st.loop;
  await view('square-island', lx, ground + 3, lz, back + 180, 15, testInfo);
});
