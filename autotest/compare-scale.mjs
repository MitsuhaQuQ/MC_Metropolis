// Run after the two client runs. Independent green runs do not guarantee a matched pair.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT } from './lib/server.mjs';

const debug = join(ROOT, 'build', 'debug');
function read(group, layout, file) {
  return JSON.parse(readFileSync(join(debug, group, 'layout-' + layout, file), 'utf8'));
}

const before = read('residential-scale', 1, 'measurements.json');
const after = read('residential-scale', 5, 'measurements.json');
assert.equal(before.layout, '1');
assert.equal(after.layout, '5');
assert.equal(before.seed, after.seed);
assert.deepEqual(before.camera, after.camera);
assert.deepEqual(before.clientOptions, after.clientOptions);
for (const direction of ['along', 'across']) {
  const camera = run => {
    const v = run.views.find(view => view.direction === direction);
    assert.ok(v, 'missing client view: ' + direction);
    return [v.state.x, v.state.y, v.state.z, v.yaw, v.pitch, v.width, v.height];
  };
  assert.deepEqual(camera(before), camera(after), direction + ' camera or resolution changed');
}

const facades = {};
for (const type of ['HOUSE', 'SHOP']) {
  const old = before.frontages.find(f => f.type === type);
  const current = after.frontages.find(f => f.type === type);
  assert.equal(current.id, old.id, type + ' is a different lot');
  assert.ok(current.measuredClearance.max > old.measuredClearance.max, type + ' has no measured headroom gain');
  facades[type] = { before: old, after: current };
}
const station = {};
for (const layout of [1, 5]) {
  const { cameraX, cameraZ, yaw, floor } = read('station-scale', layout, 'station.json');
  const lanes = read('station-scale', layout, 'approach.json');
  const centre = lanes.find(l => l.side === 0);
  const rises = centre.steps.slice(1).map(s => s.rise);
  assert.ok(rises.every(Number.isFinite));
  station[layout] = { cameraX, cameraZ, yaw, feet: floor.top, maxRise: Math.max(...rises),
    firstStepOverOne: centre.steps.slice(1).find(s => s.rise > 1) ?? null };
}
for (const key of ['cameraX', 'cameraZ', 'yaw']) assert.equal(station[1][key], station[5][key]);
assert.ok(station[1].firstStepOverOne, 'the baseline must reproduce the step');
assert.ok(station[5].maxRise <= 1, 'the corrected approach still has a step');
const route = read('station-scale', 5, 'lower-entrance.json');
assert.ok(route.length > 12 && route.at(-1).feet - route[0].feet > 4);
for (const [i, column] of route.entries()) {
  assert.ok(Number.isFinite(column.feet), 'missing floor at ' + column.x + ',' + column.z);
  assert.ok(column.headroom == null || column.headroom >= 2, 'blocked lower entrance');
  if (i > 0) assert.ok(Math.abs(column.feet - route[i - 1].feet) <= 1);
}
station[5].lowerEntrance = { low: route[0].feet, hall: route.at(-1).feet, samples: route.length };
const file = join(debug, 'residential-scale', 'comparison.json');
writeFileSync(file, JSON.stringify({ seed: before.seed, camera: before.camera, facades, station }, null, 2));
console.log('Verified paired street and station observations in', file);
