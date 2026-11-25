// The user's 0.0.9/0.0.10 world (seed -3822740797505900337, its MTM_worldgen settings from level.dat put in the dev
// server's config/mtm_worldgen.json first): the spots the user reported round the lake city, generated for the blocks
// to be read from the saved world and compared with the user's. Run with AUTOTEST_SEED=-3822740797505900337.
import { test } from '@playwright/test';
import { server } from '../lib/server.mjs';

const SPOTS = [
  { name: 'sea junction', x: 7766, z: -5756 },
  { name: 'bridge cut short', x: 6514, z: -4886 },
  { name: 'levee broken', x: 6381, z: -4861 },
  { name: 'promenade on water', x: 6487, z: -4944 },
  { name: 'tall viaducts', x: 5669, z: -4913 },
  { name: 'road on walls', x: 5834, z: -4491 },
  { name: 'cliffs and lava', x: 5628, z: -4502 },
  { name: 'mess', x: 5517, z: -4233 },
];

test('the user lake city spots', async () => {
  test.setTimeout(3_600_000);
  for (const s of SPOTS) {
    const planned = await server.planAround(s.x, s.z);
    console.log(`planned ${s.name}`, JSON.stringify(planned).slice(0, 200));
    const r = await server.generate(s.x - 32, s.z - 32, s.x + 31, s.z + 31, 1800);
    console.log(`generated ${s.name}`, JSON.stringify(r).slice(0, 200));
  }
  await server.command('save-all flush');
});
