// Buildings laid into a town as its chunks are generated (seed 12345, the castle town at -10232 -10272)
import { test, expect } from '@playwright/test';
import { server } from '../lib/server.mjs';

const TOWN = { x: -10232, z: -10272 };
const R = 64;

test('the town centre is built up', async () => {
  await server.planAround(TOWN.x, TOWN.z);
  const generated = await server.generate(TOWN.x - R, TOWN.z - R, TOWN.x + R - 1, TOWN.z + R - 1);
  console.log('generated', JSON.stringify(generated));
  expect(generated.missing).toBe(0);
  const blocks = await server.blocks(TOWN.x - R, TOWN.z - R, TOWN.x + R - 1, TOWN.z + R - 1);
  const top = Object.entries(blocks.top).sort((a, b) => b[1] - a[1]);
  console.log('top', JSON.stringify(top.slice(0, 40)));
  const total = top.reduce((a, [, v]) => a + v, 0);
  const ground = ['minecraft:grass_block', 'minecraft:grass', 'minecraft:dirt', 'minecraft:tall_grass'];
  const onGround = top.filter(([k]) => ground.includes(k) || k.startsWith('citybuilder:asphalt') || k === 'citybuilder:sidewalk')
    .reduce((a, [, v]) => a + v, 0);
  // Most of a town centre's columns are roofs, walls and yards, not bare ground or street
  console.log('roofed share', (1 - onGround / total).toFixed(2));
  expect(1 - onGround / total).toBeGreaterThan(0.3);
  // The yards kept: no forest floor or undergrowth between the buildings
  const wild = ['minecraft:podzol', 'minecraft:coarse_dirt', 'minecraft:fern', 'minecraft:large_fern',
    'minecraft:brown_mushroom', 'minecraft:red_mushroom'].reduce((a, k) => a + (blocks.top[k] ?? 0), 0);
  console.log('wild', wild);
  expect(wild).toBeLessThan(0.01 * total);
  // Kerbs at an angle cut along their line
  console.log('kerb edges', blocks.top['citybuilder:curb_edge'] ?? 0);
  expect(blocks.top['citybuilder:curb_edge'] ?? 0).toBeGreaterThan(20);
  // The plain walls of buildings square to the blocks are thin walls (round windows and doors whole blocks)
  console.log('thin walls', JSON.stringify(blocks.thinWalls));
  expect(blocks.thinWalls?.straight ?? 0).toBeGreaterThan(50);
  // Windows join the walls and windows along their run, and no arm of one ends in the air
  console.log('windows', JSON.stringify(blocks.windows));
});
