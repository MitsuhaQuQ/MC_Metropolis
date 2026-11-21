// Intercity roads laid outside the towns as their chunks are generated (seed 12345, around the castle town at -10232 -10272)
import { test, expect } from '@playwright/test';
import { server } from '../lib/server.mjs';

const TOWN = { x: -10232, z: -10272 };
const R = 40;

/** Generates the chunks round a point and counts what tops their columns. */
async function around(p) {
  const x = Math.round(p.x);
  const z = Math.round(p.z);
  const generated = await server.generate(x - R, z - R, x + R - 1, z + R - 1);
  expect(generated.missing).toBe(0);
  const blocks = await server.blocks(x - R, z - R, x + R - 1, z + R - 1);
  expect(blocks.unloaded).toBe(0);
  return blocks;
}

test('a road on the ground is paved and marked, graded to its height, clear overhead', async () => {
  // Routed ahead on the planner's thread, as for a player there
  await server.planAround(TOWN.x, TOWN.z);
  const p = await server.road(TOWN.x, TOWN.z, 'SURFACE');
  console.log('road', JSON.stringify(p));
  expect(p.found).toBe(true);
  const blocks = await around(p);
  console.log('blocks', JSON.stringify(blocks));
  const top = blocks.top;
  const asphalt = top['citybuilder:asphalt'] ?? 0;
  const lines = (top['citybuilder:asphalt_line_white'] ?? 0) + (top['citybuilder:asphalt_line_yellow'] ?? 0);
  // A road 8 to 20 wide crosses an 80 square: several hundred columns
  expect(asphalt + lines).toBeGreaterThan(300);
  expect(lines).toBeGreaterThan(40);
  // Laid at the road's height, within a few blocks (the smooth ground the plan reads against the detailed one)
  const heights = Object.keys(blocks.pavedHeights).map(Number);
  const near = heights.filter(h => Math.abs(h - p.road) <= 8).length;
  expect(near).toBeGreaterThan(0);
  const paved = Object.values(blocks.pavedHeights).reduce((a, b) => a + b, 0);
  expect(blocks.pavedCovered).toBeLessThan(0.02 * paved);
});

test('a bridge has its deck and parapets', async () => {
  const p = await server.road(TOWN.x, TOWN.z, 'BRIDGE');
  console.log('bridge', JSON.stringify(p));
  test.skip(!p.found, 'no bridge within 8 km');
  // Over water the deck clears the surface, not laid on it
  expect(p.road - p.ground).toBeGreaterThanOrEqual(5);
  const blocks = await around(p);
  const top = blocks.top;
  console.log('blocks', JSON.stringify(top));
  expect(top['citybuilder:concrete_parapet'] ?? 0).toBeGreaterThan(20);
});
