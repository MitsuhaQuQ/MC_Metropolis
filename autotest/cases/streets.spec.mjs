// Streets laid into a town as its chunks are generated (seed 12345, the castle town at -10232 -10272)
import { test, expect } from '@playwright/test';
import { server } from '../lib/server.mjs';

const TOWN = { x: -10232, z: -10272 };
const R = 64;

test('a town planned ahead is generated without holding up the server', async () => {
  await server.planAround(TOWN.x, TOWN.z);
  const generated = await server.generate(TOWN.x - R, TOWN.z - R, TOWN.x + R - 1, TOWN.z + R - 1);
  console.log('generated', generated);
  expect(generated.missing).toBe(0);
  // The plan is made on the planner's thread and the chunks on the generator's: the server keeps ticking
  expect(generated.slowestTickMs).toBeLessThan(2000);
});

test('the streets are paved, marked and clear overhead', async () => {
  const blocks = await server.blocks(TOWN.x - R, TOWN.z - R, TOWN.x + R - 1, TOWN.z + R - 1);
  console.log('blocks', JSON.stringify(blocks));
  expect(blocks.unloaded).toBe(0);
  const top = blocks.top;
  const asphalt = (top['citybuilder:asphalt'] ?? 0);
  const lines = (top['citybuilder:asphalt_line_white'] ?? 0) + (top['citybuilder:asphalt_line_yellow'] ?? 0);
  const sidewalk = top['citybuilder:sidewalk'] ?? 0;
  expect(asphalt).toBeGreaterThan(500);
  expect(lines).toBeGreaterThan(50);
  expect(sidewalk).toBeGreaterThan(50);
  // Nothing grown or built over the paving
  const paved = Object.values(blocks.pavedHeights).reduce((a, b) => a + b, 0);
  expect(blocks.pavedCovered).toBeLessThan(0.01 * paved);
});
