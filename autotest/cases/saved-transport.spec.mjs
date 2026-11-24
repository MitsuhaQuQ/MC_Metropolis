import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, server } from '../lib/server.mjs';

const folder = join(ROOT, 'build', 'debug', 'transport-replay');

test('the saved arterial and levee survive chunk generation', async ({}, info) => {
  test.skip(!process.env.AUTOTEST_REPLAY && !process.env.AUTOTEST_BASELINE,
    'requires the captured city plan or its generated replay');
  expect((await server.worldInfo()).seed).toBe('8689008927313558369');
  mkdirSync(folder, { recursive: true });
  const evidence = { seam: [], bank: [], underRail: [] };
  try {
    await server.planAround(3273, -5745);
    expect((await server.generate(3254, -5766, 3290, -5734)).missing).toBe(0);
    for (let z = -5755; z <= -5743; z++) {
      const p = await server.columnProfile(3276, z, 68, 78);
      expect(p.loaded).toBe(true);
      const surface = p.blocks.findLast(b => b.top != null);
      evidence.seam.push({ x: 3276, z, surface });
      if(z===-5743 && surface?.id==='citybuilder:curb_edge') {
        // The fixed north/south transect reaches the edge of the oblique
        // carriageway here. Follow the inside of that kerb for the last column.
        const adjacent=await Promise.all([-1,1].map(dx=>server.columnProfile(3276+dx,z,68,78)));
        const pavements=adjacent.map(p=>p.blocks.findLast(b=>b.top!=null)?.id);
        expect(pavements.some(id=>/^citybuilder:(asphalt|manhole|drain_grate)/.test(id))).toBe(true);
      } else {
        expect(surface?.id, 'broken carriageway at Z=' + z).toMatch(/^citybuilder:(asphalt|manhole|drain_grate)/);
      }
    }
    evidence.seamRoute=await server.walkRoute({mode:'drive',x:3269,z:-5739,y:73,endX:3275,endZ:-5753,endY:73,margin:8});
    expect(evidence.seamRoute.found,'drive across the original missing segment').toBe(true);
    await server.planAround(3250, -6560);
    expect((await server.generate(3220, -6578, 3280, -6528)).missing).toBe(0);
    for (const [x, z, crest] of [[3226,-6576,77.75],[3236,-6568,77.63],[3242,-6568,77.55],
      [3252,-6560,77.36],[3270,-6552,76.88]]) {
      const p = await server.columnProfile(x, z, 62, 82);
      expect(p.loaded).toBe(true);
      const paving = p.blocks.findLast(b => b.id.startsWith('citybuilder:asphalt'));
      evidence.bank.push({ x, z, crest, paving });
      expect(paving?.top, 'no bank road at ' + x + ',' + z).toBeDefined();
      expect(Math.abs(paving.top - crest)).toBeLessThanOrEqual(1);
    }
    for (const x of [3259,3260,3261]) {
      const p = await server.columnProfile(x, -6538, 60, 85);
      expect(p.loaded).toBe(true);
      const floor = p.blocks.findLast(b => b.id.startsWith('citybuilder:asphalt'));
      const beam = floor && p.blocks.find(b => b.bottom != null && b.bottom >= floor.top && b.y > floor.y);
      evidence.underRail.push({ x, floor, beam });
      expect(floor?.top).toBeDefined();
      expect(beam?.bottom).toBeDefined();
      expect(beam.bottom - floor.top).toBeGreaterThanOrEqual(4);
    }
    evidence.lowApproach=[];
    for(let z=-6536;z<=-6521;z++) {
      const cx=3260.7+(z+6536)*0.13;
      const row=[];
      const surfaces=[];
      for(const lateral of [-2,0,2]){
        const x=Math.floor(cx+lateral);
        const p=await server.columnProfile(x,z,61,70);
        const road=p.blocks.findLast(b=>b.top!=null && /^citybuilder:(asphalt|manhole|drain_grate|side_ditch)/.test(b.id)
          && !p.blocks.some(o=>o.y>b.y && o.bottom!=null && o.bottom<b.top+2));
        expect(road?.top,'low carriageway '+x+','+z).toBeDefined();
        row.push(road.top);
        surfaces.push({x,road,blocks:p.blocks.filter(b=>b.top!=null)});
      }
      evidence.lowApproach.push({z,row,surfaces});
    }
    for(let k=1;k<evidence.lowApproach.length;k++) for(let i=0;i<3;i++)
      expect(Math.abs(evidence.lowApproach[k].row[i]-evidence.lowApproach[k-1].row[i]),
        'low branch grade at '+evidence.lowApproach[k].z).toBeLessThanOrEqual(0.5);
    evidence.footRoute=await server.walkRoute({mode:'drive',x:3262,z:-6521,y:67,endX:3244,endZ:-6544,endY:67,margin:12});
    expect(evidence.footRoute.found,'low branch must turn onto the bank-foot road').toBe(true);
    expect((await server.generate(3246,-6544,3511,-6420)).missing).toBe(0);
    evidence.alternateAccess=await server.walkRoute({mode:'drive',x:3262,z:-6521,y:67,
      endX:3495,endZ:-6452,endY:73,margin:24});
    expect(evidence.alternateAccess.found,'toe road must reach a feasible entry onto the crest').toBe(true);
    const gap=await server.columnProfile(3260,-6547,63,80);
    expect(gap.blocks.some(b=>b.id.startsWith('citybuilder:asphalt')),'the impossible shortcut up the bank remains').toBe(false);
  } finally {
    const file = join(folder, 'columns.json');
    writeFileSync(file, JSON.stringify(evidence, null, 2));
    await info.attach('actual columns', { path: file, contentType: 'application/json' });
  }
});
