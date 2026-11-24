import { test, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, server } from '../lib/server.mjs';

for (const kind of ['main','local']) test('each through track continues at the '+kind+' station', async ({}, info) => {
  const result=await server.stationEnds(-10560,-8064,kind);
  expect(result.found).toBe(true);
  const station=result.station;
  expect(station.ends.length).toBeGreaterThan(0);
  const directory=join(ROOT,'build','debug','rail-interfaces');
  mkdirSync(directory,{recursive:true});
  const report={x:station.x,z:station.z,tracks:station.tracks,ends:[]};
  const failures=[];
  try{
    for(const edge of station.ends){
      const samples=edge.columns;
      const xs=samples.map(q=>q[0]),zs=samples.map(q=>q[2]);
      await server.generate(Math.floor(Math.min(...xs))-2,Math.floor(Math.min(...zs))-2,
        Math.ceil(Math.max(...xs))+2,Math.ceil(Math.max(...zs))+2);
      const measured=[];
      for(const [wx,expected,wz] of samples){
        const x=Math.floor(wx), z=Math.floor(wz);
        const p=await server.columnProfile(x,z,expected-1,expected+4);
        expect(p.loaded).toBe(true);
        const bed=p.blocks.find(b=>b.y===expected);
        const obstruction=p.blocks.find(b=>b.y>expected && b.bottom!=null &&
          !(b.y===expected+1 && b.id.includes('joint')));
        const usable=bed.top!=null && !obstruction;
        if(!usable)failures.push({x,z,expected,track:edge.track,bed,obstruction});
        measured.push({x,z,expected,bed:bed.id,obstruction:obstruction?.id,usable});
      }
      report.ends.push({start:edge.start,track:edge.track,measured});
    }
    for(const side of new Set(station.ends.map(e=>e.start))){
      expect(station.ends.filter(e=>e.start===side).length).toBe(station.tracks.length);
    }
    expect(failures.slice(0,5),'gap or obstruction on a running track').toEqual([]);
  } finally {
    const file=join(directory,kind+'-station-ends.json');
    writeFileSync(file,JSON.stringify({...report,failures},null,2));
    await info.attach('station ends',{path:file,contentType:'application/json'});
  }
});
