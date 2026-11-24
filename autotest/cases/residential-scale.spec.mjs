// Seed 12345: a fixed sidewalk opposite housing, with a section through the first storey.
import { test, expect } from '@playwright/test';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, server } from '../lib/server.mjs';
import { client, clientRunning, RUN_CLIENT, waitForClient } from '../lib/client.mjs';

const camera = { x: -11213, z: -7430, along: [0.56548, -0.82476], across: [0.82476, 0.56548] };
const layout = process.env.AUTOTEST_LAYOUT ?? '8';
// Lots present in both saved layout generations, so comparisons follow the same frontage.
const facadeIds = { HOUSE: '138548873729532051', SHOP: '-7336973768730794225' };
const output = join(ROOT, 'build', 'debug', 'residential-scale', 'layout-' + layout);

function point(camera, along, across) {
  return [camera.x, camera.z].map((v, i) =>
    Math.floor(v + 0.5 + along * camera.along[i] + across * camera.across[i]));
}

// Only count a room under its next floor. An open yard cannot pass as a very tall room.
function clearRoom(profile, floorY, rise) {
  const floor = profile.find(b => b.y === floorY);
  const ceiling = profile.find(b => b.y === floorY + rise);
  if (floor?.top == null || ceiling?.bottom == null) return null;
  const obstruction = profile.find(b => b.y > floorY && b.y < ceiling.y && b.bottom != null);
  return (obstruction ?? ceiling).bottom - floor.top;
}

// Follow the owning plot from a known interior column, then measure its occupied floor
// in the local street frame. The facade height is measured on the street-facing edge.
async function measureFront({ x, z, plot }) {
  const alongAxis = plot.alongBuilding;
  const depthAxis = plot.intoBuilding;
  const queue = [[x, z]], visited = new Set(), floor = [], doors = [];
  for (let i = 0; i < queue.length; i++) {
    const [cx, cz] = queue[i], key = cx + ',' + cz;
    if (visited.has(key)) continue;
    visited.add(key);
    if (Math.abs(cx - camera.x) > 55 || Math.abs(cz - camera.z) > 55) {
      throw new Error('building reaches outside measured area at ' + key);
    }
    const owner = await server.plot(cx + 0.5, cz + 0.5);
    if (!owner.covered || owner.buildingId !== plot.buildingId) continue;
    const column = await server.columnProfile(cx, cz, plot.floorY, plot.floorY + Math.min(62, 8 * plot.storeys + 8));
    expect(column.loaded).toBe(true);
    const door = column.blocks.find(b => b.y <= plot.floorY + 2 && b.id.endsWith('_door') && b.state.includes('half=lower'));
    if (door) doors.push({ x: cx, z: cz, y: door.y });
    const base = column.blocks[0];
    if (base.top != null && !['minecraft:dirt', 'minecraft:grass_block', 'minecraft:stone'].includes(base.id)) {
      const overhead = column.blocks.find(b => b.y > plot.floorY && b.bottom != null);
      const clearance = overhead ? overhead.bottom - base.top : null;
      const top = column.blocks.findLast(b => b.top != null && !b.id.includes('leaves')) ?? base;
      floor.push({ x: cx, z: cz, along: cx * alongAxis[0] + cz * alongAxis[1],
        across: cx * depthAxis[0] + cz * depthAxis[1], height: top.top - plot.floorY, clearance });
    }
    queue.push([cx + 1, cz], [cx - 1, cz], [cx, cz + 1], [cx, cz - 1]);
  }
  expect(floor.length).toBeGreaterThan(1);
  const span = (key, axis) => Math.abs(axis[0]) + Math.abs(axis[1])
    + Math.max(...floor.map(p => p[key])) - Math.min(...floor.map(p => p[key]));
  const streetSide = Math.min(...floor.map(p => p.across));
  const edge = floor.filter(p => p.across <= streetSide + 1.5).map(p => p.height).sort((a, b) => a - b);
  const clear = floor.filter(p => p.clearance != null && p.clearance > 0).map(p => p.clearance).sort((a, b) => a - b);
  return { id: plot.buildingId, doors, floorCells: floor.length, clearColumns: clear.length, storeys: plot.storeys,
    measuredClearance: clear.length ? { median: clear[Math.floor(clear.length / 2)], max: clear.at(-1) } : null, width: span('along', alongAxis), depth: span('across', depthAxis), alongAxis, depthAxis,
    frontEdgeHeight: edge[Math.floor(edge.length / 2)], groundStorey: plot.groundStorey, floorY: plot.floorY, plotWidth: plot.frontage,
    plotDepth: plot.maxDepth, plannedWidth: plot.buildingFrontage, plannedDepth: plot.buildingDepth };
}

function firstRoadBreak(surface) {
  return surface.find(cell => !/^citybuilder:(asphalt(?:_|$)|manhole$|drain_grate$)/.test(cell.id));
}

test('the saved discontinuity is detected at the first grass column', () => {
  // Overworld of seed 8689008927313558369, r.6.-12.mca: top road/ground blocks at X=3276.
  const materials = ['asphalt_line_yellow', 'asphalt_line_yellow', 'asphalt_line_yellow',
    'grass_block', 'grass_block', 'grass_block', 'sidewalk', 'sidewalk', 'sidewalk', 'sidewalk', 'curb_edge', 'asphalt'];
  const surface = materials.map((name, i) => ({ x: 3276, z: -5755 + i,
    id: (name === 'grass_block' ? 'minecraft:' : 'citybuilder:') + name }));
  expect(firstRoadBreak(surface)).toEqual({ x: 3276, z: -5752, id: 'minecraft:grass_block' });
});

test('a one-block obstruction fails the residential clearance measurement', () => {
  expect(clearRoom([{ y: 70, top: 71 }, { y: 72, bottom: 72 }, { y: 74, bottom: 74 }], 70, 4)).toBe(1);
  expect(clearRoom([{ y: 70, top: 71 }], 70, 4)).toBeNull();
});

test('residential frontage and first-storey clearance at player height', async ({}, info) => {
  test.setTimeout(900_000);
  mkdirSync(output, { recursive: true });
  await server.planAround(camera.x, camera.z);
  const generated = await server.generate(camera.x - 56, camera.z - 56, camera.x + 55, camera.z + 55);
  expect(generated.missing).toBe(0);
  const ground = await server.columnProfile(camera.x, camera.z, 104, 128);
  expect(ground.loaded).toBe(true);
  const paving = ground.blocks.findLast(b => b.id.includes('sidewalk') && b.top != null);
  expect(paving, 'both layouts must have a sidewalk at the fixed camera').toBeDefined();

  const samples = [], buildings = new Map(), fronts = new Map();
  const report = { seed: '12345', layout, camera, feetY: paving.top, samples, buildings: [], views: [] };
  try {
    report.crossStreet = [];
    for (const across of [4, 5, 6]) {
      const [x, z] = point(camera, 0, across);
      const profile = await server.columnProfile(x, z, Math.floor(paving.top) - 4, Math.floor(paving.top) + 3);
      const surface = profile.blocks.findLast(block => block.top != null);
      report.crossStreet.push({ x, z, id: surface?.id, y: surface?.y });
    }
    expect(firstRoadBreak(report.crossStreet), 'street surface between sidewalk and sample facade').toBeUndefined();
    for (let along = -30; along <= 30; along += 2) {
      for (let across = -10; across <= 40; across += 2) {
        const [x, z] = point(camera, along, across);
        const p = await server.plot(x + 0.5, z + 0.5);
        if (p.covered && facadeIds[p.type] === p.buildingId && !fronts.has(p.type)) {
          fronts.set(p.type, { x, z, plot: p });
        }
        if (!p.covered || p.type !== 'HOUSE' || p.storeys < 2) continue;
        buildings.set(p.buildingId, { id: p.buildingId, frontage: p.frontage, lotDepth: p.maxDepth,
          width: p.buildingFrontage, depth: p.buildingDepth, storeys: p.storeys, firstStorey: p.groundStorey });
        const profile = await server.columnProfile(x, z, p.floorY - 1, p.floorY + p.groundStorey + 1);
        expect(profile.loaded).toBe(true);
        const clearance = clearRoom(profile.blocks, p.floorY, p.groundStorey);
        samples.push({ x, z, buildingId: p.buildingId, clearance, blocks: profile.blocks });
      }
    }
    // A coarse transect can hit only the exterior wall of a small rotated house.
    // Sample the adjacent columns at full resolution before measuring the room.
    const visited = new Set(samples.map(s => s.x + ',' + s.z));
    for (const s of [...samples]) {
      for (const [dx, dz] of [[1, 0], [0, 1], [1, 1], [-1, 0], [0, -1]]) {
        const x = s.x + dx, z = s.z + dz, key = x + ',' + z;
        if (visited.has(key)) continue;
        visited.add(key);
        const p = await server.plot(x + 0.5, z + 0.5);
        if (!p.covered || p.buildingId !== s.buildingId) continue;
        const profile = await server.columnProfile(x, z, p.floorY, p.floorY + p.groundStorey);
        expect(profile.loaded).toBe(true);
        samples.push({ x, z, buildingId: p.buildingId, clearance: clearRoom(profile.blocks, p.floorY, p.groundStorey), blocks: profile.blocks });
      }
    }
    report.buildings = [...buildings.values()];
    expect(buildings.size, 'a fixed scene must contain a multi-storey home').toBeGreaterThan(0);
    const target = Number(layout) >= 3 ? 3 : 2;
    // Furniture, inner walls and stairs also occupy columns. Require a measured clear room in the scene.
    expect(samples.filter(s => s.clearance >= target).length, 'clear columns under an actual upper floor').toBeGreaterThan(0);
    // A one-block slice may have one clear column. Verify a 2 x 2 patch in one real room.
    for (const sample of samples.filter(s => s.clearance >= target)) {
      const patch = [{ x: sample.x, z: sample.z, clearance: sample.clearance }];
      for (const [dx, dz] of [[1, 0], [0, 1], [1, 1]]) {
        const x = sample.x + dx, z = sample.z + dz;
        const p = await server.plot(x + 0.5, z + 0.5);
        if (!p.covered || p.buildingId !== sample.buildingId) break;
        const profile = await server.columnProfile(x, z, p.floorY, p.floorY + p.groundStorey);
        const clearance = clearRoom(profile.blocks, p.floorY, p.groundStorey);
        if (clearance == null || clearance < target) break;
        patch.push({ x, z, clearance });
      }
      if (patch.length === 4) {
        report.room = patch;
        break;
      }
    }
    if (Number(layout) >= 3) expect(report.room, 'a usable room must extend in both horizontal directions').toBeDefined();
    for (const b of buildings.values()) {
      expect(b.firstStorey).toBe(target + 1);
      expect(Math.min(b.width, b.depth), 'one-block building body').toBeGreaterThan(1);
    }
    report.frontages = [];
    for (const [type, anchor] of fronts) {
      const measured = await measureFront(anchor);
      report.frontages.push({ type, ...measured });
      expect(measured.width).toBeGreaterThan(1);
      expect(measured.depth).toBeGreaterThan(1);
      if (type === 'SHOP') {
        if (Number(layout) >= 4) expect(measured.groundStorey).toBe(5);
        expect(measured.measuredClearance?.max, 'clear space over the shop floor').toBeGreaterThanOrEqual(measured.groundStorey - 1);
      }
    }
    expect([...fronts.keys()].sort()).toEqual(['HOUSE', 'SHOP']);
    if (clientRunning()) {
      if (!(await client.state()).inWorld) {
        await client.join();
        await waitForClient(s => s.inWorld, 180, 'client in world');
      }
      const options = readFileSync(join(RUN_CLIENT, 'options.txt'), 'utf8').split(/\r?\n/);
      report.clientOptions = options.filter(s => /^(fov|renderDistance|resourcePacks|graphicsMode|guiScale):/.test(s));
      await server.command('gamemode spectator @a');
      await server.command('time set 3000');
      await server.command('gamerule doDaylightCycle false');
      await server.command('weather clear');
      const views = ['along', 'across'].map(direction => ({ direction, x: camera.x + 0.5, z: camera.z + 0.5,
        feet: paving.top, axis: camera[direction] }));
      // An entrance-relative close-up supplements (but does not replace) the two identical A/B cameras.
      for (const facade of report.frontages) {
        const door = facade.doors[0];
        if (!door) continue;
        const [dx, dz] = facade.depthAxis;
        const x = Math.floor(door.x + 0.5 - 4 * dx), z = Math.floor(door.z + 0.5 - 4 * dz);
        const profile = await server.columnProfile(x, z, door.y - 8, door.y + 8);
        const floor = profile.blocks.filter(b => b.top != null && Math.abs(b.top - door.y) <= 3
          && !profile.blocks.some(o => o.y !== b.y && o.bottom != null && o.bottom < b.top + 2 && o.top > b.top))
          .sort((a, b) => Math.abs(a.top - door.y) - Math.abs(b.top - door.y))[0];
        if (floor) views.push({ direction: facade.type.toLowerCase() + '-entry', x: x + 0.5, z: z + 0.5, feet: floor.top, axis: [dx, dz] });
      }
      for (const { direction, x, z, feet, axis: [dx, dz] } of views) {
        const yaw = Math.atan2(-dx, dz) * 180 / Math.PI;
        await server.command('tp @a ' + [x, feet, z, yaw, 0].join(' '));
        await client.look(yaw, 0);
        let last = -1, steady = 0;
        const state = await waitForClient(s => {
          steady = s.renderedSections > 0 && s.renderedSections === last ? steady + 1 : 0;
          last = s.renderedSections;
          return steady >= 5 && Math.abs(s.x - x) < 0.1 && Math.abs(s.z - z) < 0.1
            && Math.abs(s.y - feet) < 0.1;
        }, 120, 'street scene rendered');
        const file = join(output, direction + '.png');
        await client.screenshot(file);
        expect(statSync(file).size).toBeGreaterThan(10000);
        await info.attach(direction, { path: file, contentType: 'image/png' });
        const png = readFileSync(file);
        report.views.push({ direction, yaw, pitch: 0, state, width: png.readUInt32BE(16), height: png.readUInt32BE(20) });
      }
    }
  } finally {
    const file = join(output, 'measurements.json');
    writeFileSync(file, JSON.stringify(report, null, 2));
    await info.attach('measurements', { path: file, contentType: 'application/json' });
  }
});
