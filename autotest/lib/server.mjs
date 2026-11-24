// The test server's TestKit interface: endpoint-server.json gives the port and the token
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import {request} from 'node:http';

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const RUN = join(ROOT, 'fabric', 'run');

function endpoint() {
  return JSON.parse(readFileSync(join(RUN, 'testkit', 'endpoint-server.json'), 'utf8'));
}

async function call(path, query = {}, body = '') {
  const { port, token } = endpoint();
  const url = new URL(`http://127.0.0.1:${port}${path}`);
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, String(v));
  return new Promise((resolve,reject) => {
    const req=request(url,{method:'POST',headers:{'X-TestKit-Token':token}},res=>{
      let text='';
      res.setEncoding('utf8');
      res.on('data',part=>{text+=part;});
      res.on('error',reject);
      res.on('end',()=>{
        try {
          const json=JSON.parse(text);
          if(res.statusCode!==200) throw new Error(`${path}: ${json.error}`);
          resolve(json);
        } catch(error) { reject(error); }
      });
    });
    req.on('error',reject);
    // In a fresh world generation can take the full TestKit deadline before the first header.
    req.setTimeout((Number(query.timeout??900)+30)*1000,()=>req.destroy(new Error(`${path} timed out`)));
    req.end(body);
  });
}

export const server = {
  command: (command) => call('/command', {}, command),
  generate: (x1, z1, x2, z2, timeout = 600) => call('/generate', { x1, z1, x2, z2, timeout }),
  blocks: (x1, z1, x2, z2) => call('/blocks', { x1, z1, x2, z2 }),
  columnProfile: (x, z, low, high, streetPlan = false) =>
    call('/column-profile', { x, z, low, high, ...(streetPlan ? {streetPlan: 1} : {}) }),
  tick: () => call('/tick'),
  worldInfo: () => call('/world-info'),
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
  facilities: (x, z) => call('/facilities', { x, z }),
  rail: (x, z, kind) => call('/rail', { x, z, kind }),
  railBoundaries: (x,z) => call('/rail-boundaries',{x,z}),
  plot: (x, z) => call('/plot', { x, z }),
  /** The point of an intercity road nearest a point within 8 km, outside built land, mid-stretch of a kind (or any): {found, x, z, road, ground, kind, heading, width, key}. */
  road: (x, z, kind) => call('/road', kind ? { x, z, kind } : { x, z }),
  /** The main station's square of the town owning a point: {found, template, x, z, heading, outward, depth, along, middle, loop, mouth, ground once loaded}. */
  walkRoute: (query) => call('/walk-route', query),
  railLots: (x,z) => call('/rail-lots',{x,z}),
  stationEnds: (x, z, kind) => call('/station-ends', { x, z, kind }),
  passengerSpace: (x,z,underground,interchange) => call('/passenger-space',{x,z,underground,interchange}),
  stationContacts: (x,z) => call('/station-contacts',{x,z}),
  castleWithoutKeep: (x,z) => call('/castle-without-keep',{x,z}),
  castle: (x, z) => call('/castle', { x, z }),
  station: (x, z) => call('/station', { x, z }),
  stop: () => call('/stop'),
};
