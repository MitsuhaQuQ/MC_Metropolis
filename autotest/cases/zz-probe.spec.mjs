// A look at a place in another seed's world (AUTOTEST_SEED, PROBE_X, PROBE_Z): what tops each column round it.
// A tool for finding faults, not a check.
import { test } from '@playwright/test';
import { server } from '../lib/server.mjs';

test('what lies round a place', async () => {
  test.setTimeout(900_000);
  const x = Number(process.env.PROBE_X ?? 0);
  const z = Number(process.env.PROBE_Z ?? 0);
  const r = Number(process.env.PROBE_R ?? 24);
  await server.planAround(x, z);
  const generated = await server.generate(x - r, z - r, x + r - 1, z + r - 1);
  console.log('generated', JSON.stringify(generated));
  const blocks = await server.blocks(x - r, z - r, x + r - 1, z + r - 1);
  const top = Object.entries(blocks.top).sort((a, b) => b[1] - a[1]);
  console.log('top', JSON.stringify(top));
  console.log('lava', JSON.stringify(blocks.lavaAt));
});
