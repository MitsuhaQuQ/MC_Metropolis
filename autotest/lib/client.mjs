// The test client's TestKit interface: endpoint-client.json in the client's run directory gives the port and token
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT } from './server.mjs';

export const RUN_CLIENT = join(ROOT, 'fabric', 'run-client');
export const CLIENT_ENDPOINT = join(RUN_CLIENT, 'testkit', 'endpoint-client.json');

export function clientRunning() {
  return existsSync(CLIENT_ENDPOINT);
}

async function call(path, query = {}) {
  const { port, token } = JSON.parse(readFileSync(CLIENT_ENDPOINT, 'utf8'));
  const url = new URL(`http://127.0.0.1:${port}${path}`);
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, String(v));
  const response = await fetch(url, { method: 'POST', headers: { 'X-TestKit-Token': token } });
  const json = await response.json();
  if (!response.ok) throw new Error(`${path}: ${json.error}`);
  return json;
}

export const client = {
  state: () => call('/state'),
  join: (address = '127.0.0.1:25566') => call('/join', { address }),
  look: (yaw, pitch) => call('/look', { yaw, pitch }),
  screenshot: async (file) => {
    const view=await call('/screenshot',{file});
    const iris=join(RUN_CLIENT,'config','iris.properties');
    if(view.irisInstalled && existsSync(iris)) {
      view.shaderOptions=readFileSync(iris,'utf8').split(/\r?\n/).filter(line=>/^(enableShaders|shaderPack)=/.test(line));
    }
    writeFileSync(file+'.json',JSON.stringify(view,null,2));
    return view;
  },
  map: (query = {}) => call('/map', query),
  quit: () => call('/quit'),
};

/** Waits until a check of the client's state holds, polling every second. */
export async function waitForClient(check, seconds, what) {
  const until = Date.now() + seconds * 1000;
  let state = null;
  while (Date.now() < until) {
    state = await client.state();
    if (check(state)) return state;
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`timed out waiting for the client: ${what} (last state ${JSON.stringify(state)})`);
}
