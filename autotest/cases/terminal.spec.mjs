// Airport terminals in the game: the structure templates written by tools/assets/plateau_terminals.py build are put
// into the test world and placed in the air, then the test client photographs them inside and out, so a person can
// check this mod's airport blocks (curtain walls, metal roofs, lights, seats, signs, boards, bridges, belts, escalator
// steps) as the game draws them. Skipped without the test client (node run.mjs --client) or without the templates.
import { test, expect } from '@playwright/test';
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { RUN, server } from '../lib/server.mjs';
import { client, clientRunning, waitForClient } from '../lib/client.mjs';

const TEMPLATES = process.env.TERMINALS ?? 'F:/MC/citybuilder/core/build/plateau/airports/terminals';
const SCREENS = join(process.cwd(), 'test-results', 'screens');
const Y = 150;

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

/** Puts a template into the world's generated structures and places it with its corner at (x, Y, z). */
async function place(slug, x, z) {
  const model = JSON.parse(readFileSync(join(TEMPLATES, 'models', `${slug}.json`), 'utf8').slice(0, 2000)
    .replace(/,"units".*$/s, '}'));
  const dir = join(RUN, 'autotest', 'generated', 'minecraft', 'structures');
  mkdirSync(dir, { recursive: true });
  copyFileSync(join(TEMPLATES, `${slug}.nbt`), join(dir, `${slug}.nbt`));
  const [sx, , sz] = model.size;
  await server.generate(x - 32, z - 32, x + sx + 32, z + sz + 32);
  const out = await server.command(`place template minecraft:${slug} ${x} ${Y} ${z}`);
  return { size: model.size, levels: model.levels, out };
}

test('airport terminals seen from the test client', async ({}, testInfo) => {
  test.skip(!clientRunning(), 'no test client (node run.mjs --client)');
  test.skip(!existsSync(join(TEMPLATES, 'takamatsu.nbt')), 'no terminal templates (plateau_terminals.py build)');
  test.setTimeout(30 * 60 * 1000);
  const state = await client.state();
  if (!state.inWorld) {
    await client.join();
    await waitForClient((s) => s.inWorld, 180, 'in the world');
  }
  await server.command('gamemode spectator @a');
  await server.command('time set noon');
  await server.command('gamerule doDaylightCycle false');
  await server.command('weather clear');
  // Takamatsu (TIMBER: clear glass, green carpet): the hall from x+6, z+20, 165 x 55; floors at Y, Y+6, Y+11
  const X = 3000;
  const Z = 3000;
  const t = await place('takamatsu', X, Z);
  await testInfo.attach('place takamatsu', { body: JSON.stringify(t.out), contentType: 'application/json' });
  const hx = X + 6;
  const hz = Z + 20;
  await view('terminal-apron', hx + 190, Y + 40, hz + 120, 135, 25, testInfo);
  await view('terminal-landside', hx - 20, Y + 18, hz - 30, -45, 12, testInfo);
  await view('terminal-checkin', hx + 30, Y + 1, hz + 4, 0, 8, testInfo);
  await view('terminal-claim', hx + 125, Y + 1, hz + 6, 10, 8, testInfo);
  await view('terminal-security', hx + 41, Y + 7, hz + 3, 0, 8, testInfo);
  await view('terminal-lounge', hx + 70, Y + 7, hz + 40, 20, 5, testInfo);
  await view('terminal-bridge', hx + 75, Y + 7, hz + 68, 180, 5, testInfo);
  await view('terminal-deck', hx + 60, Y + 17, hz + 10, 30, 15, testInfo);
  // At night the lights
  await server.command('time set midnight');
  await view('terminal-night', hx + 190, Y + 40, hz + 120, 135, 25, testInfo);
  await view('terminal-night-lounge', hx + 70, Y + 7, hz + 40, 20, 5, testInfo);
  await server.command('time set noon');
  // Fukuoka (SILVER: grey glass, blue carpet), beside it
  const F = 3400;
  await place('fukuoka_domestic', F, Z);
  await view('fukuoka-apron', F + 420, Y + 70, Z + 230, 135, 25, testInfo);
  await view('fukuoka-concourse', F + 60, Y + 8, Z + 24 + 94 + 16, -90, 5, testInfo);
});
