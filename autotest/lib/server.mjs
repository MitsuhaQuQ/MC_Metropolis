// The test server's TestKit interface: endpoint-server.json gives the port and the token
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const RUN = join(ROOT, 'fabric', 'run');

function endpoint() {
  return JSON.parse(readFileSync(join(RUN, 'testkit', 'endpoint-server.json'), 'utf8'));
}

async function call(path, query = {}, body = '') {
  const { port, token } = endpoint();
  const url = new URL(`http://127.0.0.1:${port}${path}`);
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, String(v));
  const response = await fetch(url, { method: 'POST', headers: { 'X-TestKit-Token': token }, body });
  const json = await response.json();
  if (!response.ok) throw new Error(`${path}: ${json.error}`);
  return json;
}

export const server = {
  command: (command) => call('/command', {}, command),
  generate: (x1, z1, x2, z2, timeout = 600) => call('/generate', { x1, z1, x2, z2, timeout }),
  blocks: (x1, z1, x2, z2) => call('/blocks', { x1, z1, x2, z2 }),
  tick: () => call('/tick'),
  planAround: (x, z) => call('/plan-around', { x, z }),
  /** The nearest piece of a class of street (BOULEVARD, ARTERIAL, ...) in this world's plan: {found, x, z, heading, width}. */
  /** The nearest stop line before a junction of marked streets: {found, x, z, heading of travel, ground}. */
  stopLine: (x, z) => call('/stop-line', { x, z }),
  /** The middle of the embankment of the town at a point nearest it. */
  embankment: (x, z) => call('/embankment', { x, z }),
  /** Every intercity road within r of a point. */
  roadsNear: (x, z, r = 40) => call('/roads-near', { x, z, r }),
  street: (x, z, streetClass, end = false, structure = undefined) =>
    call('/street', { x, z, class: streetClass, end: end ? 1 : 0, ...(structure ? { structure } : {}) }),
  /** The nearest point of a canal (its middle third) in this world's plan: {found, x, z, heading, ground once loaded}. */
  canal: (x, z) => call('/canal', { x, z }),
  /** The airport nearest a point within 40 km, as this world sited it: {found, kind, x, z, heading, length, width, elevation, threshold, apron}. */
  airport: (x, z) => call('/airport', { x, z }),
  /** The point of an intercity road nearest a point within 8 km, outside built land, mid-stretch of a kind (or any): {found, x, z, road, ground, kind, heading, width, key}. */
  road: (x, z, kind) => call('/road', kind ? { x, z, kind } : { x, z }),
  /** The main station's square of the town owning a point: {found, template, x, z, heading, outward, depth, along, middle, loop, mouth, ground once loaded}. */
  station: (x, z) => call('/station', { x, z }),
  stop: () => call('/stop'),
};
