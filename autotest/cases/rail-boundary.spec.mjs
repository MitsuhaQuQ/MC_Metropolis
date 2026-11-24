import {test,expect} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {ROOT,server} from '../lib/server.mjs';

test('the regional viaduct continues onto its urban approach at the same elevation',async()=>{
  const {boundaries}=await server.railBoundaries(-10232,-10272);
  const seam=boundaries.filter(b=>b.kind==='VIADUCT').sort((a,b)=>(b.bed-b.ground)-(a.bed-a.ground))[0];
  expect(seam,'a regional viaduct meeting a built-up town').toBeDefined();
  expect(seam.bed-seam.ground).toBeGreaterThan(10);
  const x=Math.floor(seam.x),z=Math.floor(seam.z),datum=Math.floor(seam.bed);
  expect((await server.generate(x-32,z-32,x+32,z+32)).missing).toBe(0);
  const report={seam,tracks:[]};
  const out=join(ROOT,'build','debug','rail-interfaces');
  mkdirSync(out,{recursive:true});
  try {
    for(const track of [-1,1]) {
      const samples=[];
      report.tracks.push(samples);
      for(let d=-12;d<=28;d++) {
        const h=d<0?seam.outside:seam.inside;
        const offset=track*(seam.trunk ? 2 : 2*Math.max(0,Math.min(1,d/24)));
        const wx=Math.floor(seam.x+Math.cos(h)*d-Math.sin(h)*offset);
        const wz=Math.floor(seam.z+Math.sin(h)*d+Math.cos(h)*offset);
        const {blocks}=await server.columnProfile(wx,wz,datum-2,datum+6);
        const bed=blocks.find(b=>Math.abs(b.y-datum)<=1 && b.id==='minecraft:gravel'
          && !blocks.some(obstacle=>obstacle.y>b.y && obstacle.y<=b.y+4 && obstacle.bottom!=null));
        samples.push({wx,wz,d,bed,blocks:bed?undefined:blocks});
        expect(bed,'running track at '+[wx,datum,wz]).toBeDefined();
        if(samples.length>1) expect(Math.abs(bed.y-samples.at(-2).bed.y)).toBeLessThanOrEqual(1);
      }
    }
  } finally {writeFileSync(join(out,'urban-boundary.json'),JSON.stringify(report,null,2));}
});
