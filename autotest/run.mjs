// Runs the automated in-game tests, as MTR's ui-test.sh does: a fresh world on the dev test server (fabric/run, its
// EULA accepted by the user), TestKit's endpoint awaited, Playwright Test over autotest/cases, the server stopped, and
// any new ERROR or exception in the server's log counted as a failure.
//
//   node run.mjs [--client] [playwright args]      (from autotest/; npm install first)
//
// With --client the dev test client (fabric/run-client) is started too, once the server is up, for the cases that
// look at the game through it; its window opens on the desktop.
import { spawn } from 'node:child_process';
import { copyFileSync, cpSync, realpathSync, createWriteStream, existsSync, readFileSync, rmSync, writeFileSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { join, relative, isAbsolute, sep } from 'node:path';
import { ROOT, RUN, server } from './lib/server.mjs';
import { CLIENT_ENDPOINT, client } from './lib/client.mjs';

const replay = process.env.AUTOTEST_REPLAY;
const baseline = process.env.AUTOTEST_BASELINE;
if (replay && baseline) throw new Error('Select either a saved-chunk baseline or a plan replay');
const WORLD = baseline ? 'autotest-baseline' : replay ? 'autotest-replay' : 'autotest';
const layout = process.env.AUTOTEST_LAYOUT;
if (layout !== undefined && !['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12'].includes(layout)) {
  throw new Error('AUTOTEST_LAYOUT must be 1 to 12');
}
const ENDPOINT = join(RUN, 'testkit', 'endpoint-server.json');
const LOG = join(RUN, 'logs', 'latest.log');
const WITH_CLIENT = process.argv.includes('--client');
const ARGS = process.argv.slice(2).filter((a) => a !== '--client');
// Lines of the dev server's log that are not ours to fail on: the sibling mods' own noise
const KNOWN = [/No data fixer registered/, /Failed to bind to \/127\.0\.0\.1:8888/, /Address already in use/,
  /ServletContextHandler/, /AbstractLifeCycle/, /BindException/, /Can't keep up/,
  /metropolis\/block\/BlockTurnstile/];

async function properties() {
  let source;
  const target = join(realpathSync(RUN), WORLD);
  if (existsSync(target) && relative(target,realpathSync(target))!=='')
    throw new Error('Disposable test world resolves outside its assigned directory');
  if (replay || baseline) {
    source = realpathSync(baseline || replay);
    for (const within of [relative(target,source),relative(source,target)]) {
      if (!isAbsolute(within) && within!=='..' && !within.startsWith('..'+sep))
        throw new Error('Input and disposable world directories must be separate');
    }
    if (!existsSync(join(source, 'level.dat'))) throw new Error('Replay input has no level.dat');
  }
  if (!existsSync(join(RUN, 'eula.txt'))) {
    throw new Error('fabric/run/eula.txt is missing: the Minecraft EULA must be accepted by the user first');
  }
  writeFileSync(join(RUN, 'server.properties'), [
    'level-type=mtm_worldgen\\:realistic', `level-seed=${process.env.AUTOTEST_SEED ?? '12345'}`, `level-name=${WORLD}`, 'online-mode=false',
    'enable-rcon=false', 'enable-query=false', 'server-ip=127.0.0.1', 'server-port=25566', 'spawn-protection=0',
    `view-distance=${WITH_CLIENT ? 8 : 4}`, 'simulation-distance=4', ''].join('\n'));
  rmSync(target, { recursive: true, force: true });
  if (source) {
    if (baseline) {
      const db=join(source,'citybuilder','citybuilder.db');
      cpSync(source,target,{recursive:true,dereference:true,filter:path=>{
        if ([join(source,'session.lock'),db,db+'-wal',db+'-shm'].includes(path)) return false;
        const inside=relative(source,realpathSync(path));
        if (isAbsolute(inside) || inside==='..' || inside.startsWith('..'+sep))
          throw new Error('Baseline contains a link outside the supplied world: '+path);
        return true;
      }});
    }
    const plans = join(target, 'citybuilder');
    mkdirSync(plans, { recursive: true });
    copyFileSync(join(source, 'level.dat'), join(target, 'level.dat'));
    const legacy = join(source, 'citybuilder', 'plan');
    if (!baseline && existsSync(legacy)) cpSync(legacy, join(plans, 'plan'), { recursive: true });
    const database = join(source, 'citybuilder', 'citybuilder.db');
    if (existsSync(database)) {
      const { DatabaseSync, backup } = await import('node:sqlite');
      const original = new DatabaseSync(database, { readOnly: true });
      const cloned = join(plans, 'citybuilder.db');
      try { await backup(original, cloned); } finally { original.close(); }
      if (layout !== undefined) {
        const db = new DatabaseSync(cloned);
        try {
          db.prepare('INSERT OR REPLACE INTO plans(key,data) VALUES(?,?)').run('layout/building_scale', Buffer.from([Number(layout)]));
        } finally { db.close(); }
      }
    }
  }
  if (layout !== undefined) {
    const directory = join(RUN, WORLD, 'citybuilder', 'plan', 'layout');
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, 'building_scale.bin'), Buffer.from([Number(layout)]));
  }
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
  if (!exited[name]) await new Promise(resolve => {
    const done=()=>{clearTimeout(timer);child.off('exit',done);resolve();};
    const timer=setTimeout(done,seconds*1000);
    child.once('exit',done);
  });
  if (!exited[name] && pid) { try { process.kill(pid); } catch { /* exited */ } }
}

function logErrors() {
  if (!existsSync(LOG)) return [];
  return readFileSync(LOG, 'utf8').split(/\r?\n/)
    .filter((l) => /\/ERROR\]|Exception/.test(l) && !KNOWN.some((k) => k.test(l)));
}

const started = new Date();
const resultDir = join(ROOT,'autotest','test-results',started.toISOString().replace(/[:.]/g,'-')+'-'+process.pid);
mkdirSync(resultDir,{recursive:true});
const manifest = {started:started.toISOString(), args:process.argv.slice(2), layout:layout??null,
  replay:replay??null, baseline:baseline??null, world:WORLD, client:WITH_CLIENT, seed:null, status:'starting'};
const persist = () => writeFileSync(join(resultDir,'run.json'),JSON.stringify(manifest,null,2));
persist();
function archiveEvidence(directory, destination) {
  if (!existsSync(directory)) return;
  for (const file of readdirSync(directory,{withFileTypes:true})) {
    const path=join(directory,file.name);
    if (file.isDirectory()) archiveEvidence(path,join(destination,file.name));
    else if (file.isFile() && /\.(json|png)$/.test(file.name) && statSync(path).mtimeMs>=started.getTime()) {
      mkdirSync(destination,{recursive:true});
      copyFileSync(path,join(destination,file.name));
    }
  }
}
const serverLog = join(RUN, 'autotest-server.log');
const clientLog = join(ROOT, 'fabric', 'run-client', 'autotest-client.log');
let serverProcess = null;
let clientProcess = null;
let failed = false;
try {
  await properties();
  mkdirSync(join(ROOT, 'fabric', 'run-client'), { recursive: true });
  serverProcess = gradle(':fabric:runServer', serverLog, 'server');
  await waitFor(() => existsSync(ENDPOINT), 600, 'server', serverLog);
  manifest.seed = (await server.worldInfo()).seed;
  if (WITH_CLIENT) {
    clientProcess = gradle(':fabric:runClient', clientLog, 'client');
    await waitFor(() => existsSync(CLIENT_ENDPOINT), 600, 'client', clientLog);
  }
  const cli = join(ROOT, 'autotest', 'node_modules', '@playwright', 'test', 'cli.js');
  manifest.status='testing';
  persist();
  const tests = spawn(process.execPath, [cli, 'test', ...ARGS], { stdio: 'inherit',
    env:{...process.env,AUTOTEST_RESULT_DIR:resultDir} });
  const code = await new Promise((resolve,reject) => {tests.once('error',reject);tests.once('exit',resolve);});
  manifest.testExitCode=code;
  failed = code !== 0;
} catch (error) {
  failed = true;
  manifest.error = String(error);
  console.error(error);
} finally {
  if (clientProcess) await stop(clientProcess, 'client', CLIENT_ENDPOINT, () => client.quit(), 30);
  if (serverProcess) await stop(serverProcess, 'server', ENDPOINT, () => server.stop(), 60);
}
const errors = serverProcess ? logErrors() : [];
if (errors.length) {
  console.error(`The server logged ${errors.length} error(s):`);
  for (const e of errors.slice(0, 20)) console.error('  ' + e);
  failed = true;
}
archiveEvidence(join(ROOT,'build','debug'),join(resultDir,'evidence'));
for (const [file,name] of [[serverLog,'server.log'],[clientLog,'client.log']]) {
  if (existsSync(file) && statSync(file).mtimeMs>=started.getTime()) copyFileSync(file,join(resultDir,name));
}
manifest.status=failed?'failed':'passed';
manifest.finished=new Date().toISOString();
manifest.serverErrors=errors;
persist();
console.log('Run report: '+resultDir);
process.exit(failed ? 1 : 0);
