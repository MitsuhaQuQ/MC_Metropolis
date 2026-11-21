// Runs the automated in-game tests, as MTR's ui-test.sh does: a fresh world on the dev test server (fabric/run, its
// EULA accepted by the user), TestKit's endpoint awaited, Playwright Test over autotest/cases, the server stopped, and
// any new ERROR or exception in the server's log counted as a failure.
//
//   node run.mjs [--client] [playwright args]      (from autotest/; npm install first)
//
// With --client the dev test client (fabric/run-client) is started too, once the server is up, for the cases that
// look at the game through it; its window opens on the desktop.
import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, RUN, server } from './lib/server.mjs';
import { CLIENT_ENDPOINT, client } from './lib/client.mjs';

const WORLD = 'autotest';
const ENDPOINT = join(RUN, 'testkit', 'endpoint-server.json');
const LOG = join(RUN, 'logs', 'latest.log');
const WITH_CLIENT = process.argv.includes('--client');
const ARGS = process.argv.slice(2).filter((a) => a !== '--client');
// Lines of the dev server's log that are not ours to fail on: the sibling mods' own noise
const KNOWN = [/No data fixer registered/, /Failed to bind to \/127\.0\.0\.1:8888/, /Address already in use/,
  /ServletContextHandler/, /AbstractLifeCycle/, /BindException/, /Can't keep up/,
  /metropolis\/block\/BlockTurnstile/];

function properties() {
  if (!existsSync(join(RUN, 'eula.txt'))) {
    throw new Error('fabric/run/eula.txt is missing: the Minecraft EULA must be accepted by the user first');
  }
  writeFileSync(join(RUN, 'server.properties'), [
    'level-type=mtm_worldgen\\:realistic', `level-seed=${process.env.AUTOTEST_SEED ?? '12345'}`, `level-name=${WORLD}`, 'online-mode=false',
    'enable-rcon=false', 'enable-query=false', 'server-ip=127.0.0.1', 'server-port=25566', 'spawn-protection=0',
    `view-distance=${WITH_CLIENT ? 8 : 4}`, 'simulation-distance=4', ''].join('\n'));
  rmSync(join(RUN, WORLD), { recursive: true, force: true });
  rmSync(ENDPOINT, { force: true });
  rmSync(CLIENT_ENDPOINT, { force: true });
  mkdirSync(join(RUN, 'testkit'), { recursive: true });
}

const exited = {};

/** Runs a Gradle task in the background, its output to a log file; records when it exits. */
function gradle(task, log, name) {
  const gradlew = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew';
  const out = createWriteStream(log);
  const child = spawn(gradlew, [task, '--console=plain'], {
    cwd: ROOT, shell: process.platform === 'win32', stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, JAVA_TOOL_OPTIONS: `-Djdk.net.unixdomain.tmpdir=${join(ROOT, '.gradle', 'uds')}` },
  });
  child.stdout.pipe(out);
  child.stderr.pipe(out);
  child.on('error', (e) => { exited[name] = String(e); });
  child.on('exit', (code) => { exited[name] = `exit code ${code}`; });
  return child;
}

async function waitFor(check, seconds, name, log) {
  const until = Date.now() + seconds * 1000;
  while (Date.now() < until) {
    if (check()) return;
    if (exited[name]) throw new Error(`the ${name} stopped before it was ready (${exited[name]}); see ${log}`);
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`timed out waiting for the ${name}`);
}

/** Asks a process to stop, then ends it by the process id it wrote if it is still there after a while. */
async function stop(child, name, endpoint, ask, seconds) {
  let pid = null;
  try { pid = JSON.parse(readFileSync(endpoint, 'utf8')).pid; } catch { /* never started */ }
  try { await ask(); } catch { /* already gone */ }
  if (!exited[name]) await new Promise((r) => { child.on('exit', r); setTimeout(r, seconds * 1000); });
  if (pid) { try { process.kill(pid); } catch { /* exited */ } }
}

function logErrors() {
  if (!existsSync(LOG)) return [];
  return readFileSync(LOG, 'utf8').split(/\r?\n/)
    .filter((l) => /\/ERROR\]|Exception/.test(l) && !KNOWN.some((k) => k.test(l)));
}

properties();
const serverLog = join(RUN, 'autotest-server.log');
const clientLog = join(ROOT, 'fabric', 'run-client', 'autotest-client.log');
mkdirSync(join(ROOT, 'fabric', 'run-client'), { recursive: true });
const serverProcess = gradle(':fabric:runServer', serverLog, 'server');
let clientProcess = null;
let failed = false;
try {
  await waitFor(() => existsSync(ENDPOINT), 600, 'server', serverLog);
  if (WITH_CLIENT) {
    clientProcess = gradle(':fabric:runClient', clientLog, 'client');
    await waitFor(() => existsSync(CLIENT_ENDPOINT), 600, 'client', clientLog);
  }
  const tests = spawn('npx', ['playwright', 'test', ...ARGS], { stdio: 'inherit', shell: true });
  const code = await new Promise((r) => tests.on('exit', r));
  failed = code !== 0;
} finally {
  if (clientProcess) await stop(clientProcess, 'client', CLIENT_ENDPOINT, () => client.quit(), 30);
  await stop(serverProcess, 'server', ENDPOINT, () => server.stop(), 60);
}
const errors = logErrors();
if (errors.length) {
  console.error(`The server logged ${errors.length} error(s):`);
  for (const e of errors.slice(0, 20)) console.error('  ' + e);
  failed = true;
}
process.exit(failed ? 1 : 0);
