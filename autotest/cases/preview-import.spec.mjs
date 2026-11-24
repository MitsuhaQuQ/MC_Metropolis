import {test,expect} from '@playwright/test';
import {join} from 'node:path';
import {mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {ROOT,server} from '../lib/server.mjs';
import {client,clientRunning,waitForClient} from '../lib/client.mjs';

test('the exported castle world opens at the main gate',async({},info)=>{
  test.skip(!process.env.AUTOTEST_BASELINE || process.env.AUTOTEST_PREVIEW_SPAWN!=='castle',
    'requires the extracted version-8 castle preview as a saved-chunk baseline');
  expect((await server.worldInfo()).seed).toBe('12345');
  const guide=readFileSync(join(process.env.AUTOTEST_BASELINE,'试玩说明.txt'),'utf8');
  const gate=guide.match(/本丸主门前\s+\/tp @s (-?\d+) (-?\d+) (-?\d+)/);
  expect(gate,'gate spawn in the exported guide').toBeTruthy();
  const [spawnX,spawnY,spawnZ]=gate.slice(1).map(Number);
  // In this north-facing gate scene the upper landing is 29 blocks in
  // from the spawn. The entire castle may translate to fit its road frontage.
  const landing=await server.columnProfile(spawnX,spawnZ+29,spawnY+3,spawnY+4);
  expect(landing.loaded,'saved upper landing already loaded in the spawn chunks').toBe(true);
  expect(landing.blocks[0].id).toBe('minecraft:stone_brick_stairs');
  expect(clientRunning(),'import smoke test needs a game client').toBe(true);
  await client.join();
  await waitForClient(s=>s.inWorld&&s.renderedSections>0,120,'imported world');
  const state=await client.state();
  expect(Math.hypot(state.x-spawnX-0.5,state.z-spawnZ-0.5),'spawn on the path facing the gate').toBeLessThan(2);
  expect(Math.abs(state.y-spawnY)).toBeLessThanOrEqual(1);
  const out=join(ROOT,'build','debug','castle-wards');
  mkdirSync(out,{recursive:true});
  const result=join(out,'import.json');
  writeFileSync(result,JSON.stringify({state,landing},null,2));
  await info.attach('imported spawn and saved stairs',{path:result,contentType:'application/json'});
});
