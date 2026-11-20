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
  stop: () => call('/stop'),
};
