// Runs the automated in-game tests, as MTR's ui-test.sh does: a fresh world on the dev test server (fabric/run, its
// EULA accepted by the user), TestKit's endpoint awaited, Playwright Test over autotest/cases, the server stopped, and
// any new ERROR or exception in the server's log counted as a failure.
//
//   node run.mjs [playwright args]      (from autotest/; npm install first)
import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, RUN, server } from './lib/server.mjs';

const WORLD = 'autotest';
const ENDPOINT = join(RUN, 'testkit', 'endpoint-server.json');
const LOG = join(RUN, 'logs', 'latest.log');
// Lines of the dev server's log that are not ours to fail on: the sibling mods' own noise
const KNOWN = [/No data fixer registered/, /Failed to bind to \/127\.0\.0\.1:8888/, /Address already in use/,
  /ServletContextHandler/, /AbstractLifeCycle/, /BindException/, /Can't keep up/,
  /metropolis\/block\/BlockTurnstile/];

function properties() {
  if (!existsSync(join(RUN, 'eula.txt'))) {
    throw new Error('fabric/run/eula.txt is missing: the Minecraft EULA must be accepted by the user first');
  }
  writeFileSync(join(RUN, 'server.properties'), [
    'level-type=mtm_worldgen\\:realistic', 'level-seed=12345', `level-name=${WORLD}`, 'online-mode=false',
    'enable-rcon=false', 'enable-query=false', 'server-ip=127.0.0.1', 'server-port=25566', 'spawn-protection=0',
    'view-distance=4', 'simulation-distance=4', ''].join('\n'));
  rmSync(join(RUN, WORLD), { recursive: true, force: true });
  rmSync(ENDPOINT, { force: true });
  mkdirSync(join(RUN, 'testkit'), { recursive: true });
}

let exited = null;

function startServer() {
  const gradle = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
  const out = createWriteStream(join(RUN, 'autotest-server.log'));
  const child = spawn(gradle, [':fabric:runServer', '--console=plain'], {
    cwd: ROOT, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, JAVA_TOOL_OPTIONS: `-Djdk.net.unixdomain.tmpdir=${join(ROOT, '.gradle', 'uds')}` },
  });
  child.stdout.pipe(out);
  child.stderr.pipe(out);
  child.on('error', (e) => { exited = String(e); });
  child.on('exit', (code) => { exited = `exit code ${code}`; });
  return child;
}

async function waitFor(check, seconds, what) {
  const until = Date.now() + seconds * 1000;
  while (Date.now() < until) {
    if (check()) return;
    if (exited) throw new Error(`the server stopped before ${what} was ready (${exited}); see fabric/run/autotest-server.log`);
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`timed out waiting for ${what}`);
}

function logErrors() {
  if (!existsSync(LOG)) return [];
  return readFileSync(LOG, 'utf8').split(/\r?\n/)
    .filter((l) => /\/ERROR\]|Exception/.test(l) && !KNOWN.some((k) => k.test(l)));
}

properties();
const child = startServer();
let failed = false;
try {
  await waitFor(() => existsSync(ENDPOINT), 600, 'the test server');
  const tests = spawn('npx', ['playwright', 'test', ...process.argv.slice(2)], { stdio: 'inherit', shell: true });
  const code = await new Promise((r) => tests.on('exit', r));
  failed = code !== 0;
} finally {
  // Asked to stop, then, as MTR's ui-test.sh does, ended by the process id it gave if still there after a minute
  let pid = null;
  try { pid = JSON.parse(readFileSync(ENDPOINT, 'utf8')).pid; } catch { /* never started */ }
  try { await server.stop(); } catch { /* already gone */ }
  if (!exited) await new Promise((r) => { child.on('exit', r); setTimeout(r, 60000); });
  if (pid) { try { process.kill(pid); } catch { /* exited */ } }
}
const errors = logErrors();
if (errors.length) {
  console.error(`The server logged ${errors.length} error(s):`);
  for (const e of errors.slice(0, 20)) console.error('  ' + e);
  failed = true;
}
process.exit(failed ? 1 : 0);
