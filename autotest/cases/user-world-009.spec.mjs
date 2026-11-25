// The user's 0.0.9 world (seed 8936058095230555848): the station deck at (-3441, -3258) whose glass sound barriers
// stood apart on the diagonal, and the branch line crossing city line 1 at (-3780, -3425) on one level. Generated here
// for the blocks to be read from the saved world. Run with AUTOTEST_SEED=8936058095230555848.
import { test } from '@playwright/test';
import { server } from '../lib/server.mjs';

const CITY = { x: -3440, z: -3291 };
const STATION = { x: -3441, z: -3258 };
const CROSSING = { x: -3780, z: -3425 };

test('the user world 0.0.9 spots', async () => {
  test.setTimeout(3_600_000);
  await server.planAround(CITY.x, CITY.z);
  await server.generate(STATION.x - 64, STATION.z - 64, STATION.x + 63, STATION.z + 63, 1800);
  await server.generate(CROSSING.x - 48, CROSSING.z - 48, CROSSING.x + 47, CROSSING.z + 47, 1800);
  await server.command('save-all flush');
});
