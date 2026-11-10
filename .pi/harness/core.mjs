import { promises as fs, existsSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import net from 'node:net';

const ignored = new Set(['.git', 'node_modules', '.gradle', 'build', 'dist', 'target', '.DS_Store']);
const digest = value => createHash('sha256').update(value).digest('hex');

export async function snapshot(root) {
  const files = {};
  async function walk(dir, prefix = '') {
    for (const entry of (await fs.readdir(dir, { withFileTypes: true })).sort((a,b) => a.name.localeCompare(b.name))) {
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (ignored.has(entry.name) || rel === '.pi/runs') continue;
      const full = path.join(dir, entry.name);
      if (entry.isSymbolicLink()) files[rel] = `symlink:${await fs.readlink(full)}`;
      else if (entry.isDirectory()) await walk(full, rel);
      else if (entry.isFile()) files[rel] = digest(await fs.readFile(full));
    }
  }
  await walk(root);
  return files;
}

export function fingerprint(files) {
  return digest(JSON.stringify(Object.entries(files).sort(([a],[b]) => a.localeCompare(b))));
}

export function changedPaths(before, after) {
  return [...new Set([...Object.keys(before), ...Object.keys(after)])].filter(k => before[k] !== after[k]).sort();
}

export function isAllowed(relative, allowed) {
  if (relative === '..' || relative.startsWith('../') || path.isAbsolute(relative)) return false;
  return allowed.some(p => p.endsWith('/**') ? relative.startsWith(p.slice(0, -2)) : relative === p);
}

// Resolve existing ancestors too, so a not-yet-created file under a symlink is checked.
export function safeRelative(root, file) {
  let resolved = path.resolve(root, file);
  const suffix = [];
  while (!existsSync(resolved)) {
    const parent = path.dirname(resolved);
    if (parent === resolved) break;
    suffix.unshift(path.basename(resolved));
    resolved = parent;
  }
  resolved = path.join(realpathSync(resolved), ...suffix);
  return path.relative(realpathSync(root), resolved).split(path.sep).join('/');
}

export async function listTasks(root) {
  return (await fs.readdir(path.join(root, '.pi/tasks'))).filter(f => f.endsWith('.json')).sort().map(f => f.slice(0, -5));
}

export async function loadTask(root, input) {
  if (!/^[a-z0-9-]+$/.test(input)) throw new Error('작업 ID에는 영문 소문자·숫자·하이픈만 사용할 수 있습니다.');
  const matches = (await listTasks(root)).filter(id => id === input || id.startsWith(`${input}-`));
  if (matches.length !== 1) throw new Error(`작업을 하나로 찾을 수 없습니다: ${input}`);
  const task = JSON.parse(await fs.readFile(path.join(root, '.pi/tasks', `${matches[0]}.json`), 'utf8'));
  if (task.id !== matches[0] || !Array.isArray(task.allowedPaths) || !Array.isArray(task.requiredFiles)
      || task.checks?.join(',') !== 'spring-health' || task.maxVerificationRuns !== 3) throw new Error('지원하지 않는 작업 정의입니다.');
  for (const file of [task.brief, ...task.requiredFiles]) {
    const relative = safeRelative(root, file);
    if (relative === '..' || relative.startsWith('../') || path.isAbsolute(relative)) throw new Error('프로젝트 밖의 작업 경로는 사용할 수 없습니다.');
  }
  return task;
}

async function writeJson(file, data) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const tmp = `${file}.${randomUUID()}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(data, null, 2) + '\n', { mode: 0o600 });
  await fs.rename(tmp, file);
}

export class RunStore {
  constructor(root, sessionId) {
    this.root = root;
    this.dir = path.join(root, '.pi/runs', digest(sessionId).slice(0, 24));
  }
  async read() {
    try { return JSON.parse(await fs.readFile(path.join(this.dir, 'current.json'), 'utf8')); }
    catch (e) { if (e.code === 'ENOENT') return null; throw e; }
  }
  async save(state) { await writeJson(path.join(this.dir, 'current.json'), state); }
  async start(task) {
    const state = { runId: randomUUID(), task, active: true, startedAt: new Date().toISOString(),
      baseline: await snapshot(this.root), verificationRuns: 0, repairContinuations: 0, last: null };
    await this.save(state);
    return state;
  }
  async report(state, report) {
    const file = path.join(this.dir, state.runId, `check-${state.verificationRuns}.json`);
    await writeJson(file, report);
    return path.relative(this.root, file);
  }
}

export function java21Env() {
  const env = { ...process.env };
  if (process.platform === 'darwin') {
    try {
      env.JAVA_HOME = execFileSync('/usr/libexec/java_home', ['-v', '21'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
      env.PATH = `${env.JAVA_HOME}/bin:${env.PATH || ''}`;
    } catch { throw new Error('Java 21을 찾지 못했습니다. Java 21 설치·JAVA_HOME을 확인해주세요.'); }
  }
  return env;
}

export function stopProcess(child) {
  try {
    if (process.platform !== 'win32' && child.pid) process.kill(-child.pid, 'SIGTERM');
    else child.kill('SIGTERM');
  } catch { /* already exited */ }
  const timer = setTimeout(() => {
    try {
      if (process.platform !== 'win32' && child.pid) process.kill(-child.pid, 'SIGKILL');
      else child.kill('SIGKILL');
    } catch { /* already exited */ }
  }, 1500);
  timer.unref();
  child.once('close', () => clearTimeout(timer));
}

export function runCommand(command, args, { cwd, env = process.env, timeoutMs = 180000, signal } = {}) {
  return new Promise(resolve => {
    const started = Date.now();
    let output = '', timedOut = false, aborted = false, done = false;
    const child = spawn(command, args, { cwd, env, shell: false, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    const append = data => { output = (output + data.toString()).slice(-12000); };
    child.stdout.on('data', append); child.stderr.on('data', append);
    const timer = setTimeout(() => { timedOut = true; stopProcess(child); }, timeoutMs);
    const abort = () => { aborted = true; stopProcess(child); };
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    function finish(code, error) {
      if (done) return; done = true;
      clearTimeout(timer); signal?.removeEventListener('abort', abort);
      resolve({ ok: code === 0 && !timedOut && !aborted && !error, code, timedOut, aborted,
        durationMs: Date.now() - started, output: (output + (error ? `\n${error.message}` : '')).slice(-12000) });
    }
    child.on('error', error => finish(null, error));
    child.on('close', code => finish(code));
  });
}

export async function junitSummary(dir) {
  let executed = 0, failures = 0, errors = 0, skipped = 0, reports = 0;
  let names;
  try { names = await fs.readdir(dir); } catch (e) { if (e.code === 'ENOENT') return { ok: false, reports: 0, executed: 0 }; throw e; }
  for (const name of names.filter(x => x.startsWith('TEST-') && x.endsWith('.xml'))) {
    const xml = await fs.readFile(path.join(dir, name), 'utf8');
    const tag = xml.match(/<testsuite\b[^>]*>/)?.[0];
    if (!tag) continue;
    const n = attr => Number(tag.match(new RegExp(`\\b${attr}="(\\d+)"`))?.[1] || 0);
    reports++; skipped += n('skipped'); executed += n('tests') - n('skipped'); failures += n('failures'); errors += n('errors');
  }
  return { ok: reports > 0 && executed > 0 && failures === 0 && errors === 0, reports, executed, failures, errors, skipped };
}

async function unusedPort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function checkHttp(root, env, signal) {
  const libs = path.join(root, 'backend/build/libs');
  const jars = (await fs.readdir(libs)).filter(f => f.endsWith('.jar') && !f.endsWith('-plain.jar'));
  if (jars.length !== 1) return { ok: false, output: '실행 가능한 JAR가 정확히 하나여야 합니다. build/libs를 확인해주세요.' };
  const port = await unusedPort();
  const child = spawn(env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin/java') : 'java', ['-jar', path.join(libs, jars[0]),
    '--server.address=127.0.0.1', `--server.port=${port}`], {
    cwd: path.join(root, 'backend'), env, shell: false, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '', ended = false;
  const append = data => { output = (output + data.toString()).slice(-6000); };
  child.stdout.on('data', append); child.stderr.on('data', append);
  const closed = new Promise(resolve => child.once('close', () => { ended = true; resolve(); }));
  child.on('error', e => { output += e.message; ended = true; });
  const abort = () => stopProcess(child);
  signal?.addEventListener('abort', abort, { once: true });
  const started = Date.now();
  try {
    while (Date.now() - started < 60000 && !ended && !signal?.aborted) {
      try {
        const response = await fetch(`http://127.0.0.1:${port}/api/health`, { signal: AbortSignal.timeout(1200) });
        const body = await response.json();
        return { ok: response.status === 200 && body?.status === 'UP', status: response.status, body,
          durationMs: Date.now() - started, output };
      } catch { await new Promise(resolve => setTimeout(resolve, 250)); }
    }
    return { ok: false, aborted: signal?.aborted || false, durationMs: Date.now() - started, output: `서버 기동 또는 상태 확인 실패\n${output}` };
  } finally {
    signal?.removeEventListener('abort', abort);
    if (!ended) stopProcess(child);
    await closed;
  }
}

export async function springHealthCheck(root, signal) {
  const env = java21Env();
  const steps = [];
  const java = await runCommand('java', ['-version'], { cwd: root, env, signal });
  steps.push({ name: 'Java 21', ...java, ok: java.ok && /version "21[.\"]/.test(java.output) });
  if (!steps.at(-1).ok) return steps;
  for (const [name, args] of [['assemble', ['--no-daemon', 'clean', 'assemble']], ['test', ['--no-daemon', 'test', '--rerun-tasks']]]) {
    const result = await runCommand('./gradlew', args, { cwd: path.join(root, 'backend'), env, signal });
    steps.push({ name, ...result });
    if (!result.ok) return steps;
  }
  const junit = await junitSummary(path.join(root, 'backend/build/test-results/test'));
  steps.push({ name: 'JUnit executed tests', ...junit });
  if (!junit.ok) return steps;
  steps.push({ name: 'GET /api/health', ...await checkHttp(root, env, signal) });
  return steps;
}

export async function verify(store, { signal, checks = springHealthCheck } = {}) {
  const state = await store.read();
  if (!state) throw new Error('먼저 /fh-task 001로 작업을 선택하세요.');
  if (state.verificationRuns >= state.task.maxVerificationRuns) throw new Error('검증 3회 한도에 도달했습니다. 결과를 확인하고 /fh-task로 새 작업 실행을 시작하세요.');
  state.verificationRuns++;
  await store.save(state);
  const before = await snapshot(store.root);
  const outside = changedPaths(state.baseline, before).filter(p => !isAllowed(p, state.task.allowedPaths));
  const missing = state.task.requiredFiles.filter(p => !existsSync(path.join(store.root, p)));
  let steps = [];
  if (!outside.length && !missing.length && !signal?.aborted) {
    try { steps = await checks(store.root, signal); }
    catch (e) { steps = [{ name: 'verification', ok: false, output: e.message }]; }
  }
  const after = await snapshot(store.root);
  const stable = fingerprint(before) === fingerprint(after);
  const allOutside = [...new Set([...outside, ...changedPaths(state.baseline, after).filter(p => !isAllowed(p, state.task.allowedPaths))])];
  const report = { taskId: state.task.id, runId: state.runId, checkedAt: new Date().toISOString(),
    run: state.verificationRuns, status: !signal?.aborted && !allOutside.length && !missing.length && stable && steps.length > 0 && steps.every(x => x.ok) ? 'passed' : 'failed',
    missing, outsideScope: allOutside, stable, fingerprint: fingerprint(after), steps,
    changedFiles: changedPaths(state.baseline, after) };
  report.reportPath = await store.report(state, report);
  state.last = report;
  await store.save(state);
  return report;
}

export async function status(store) {
  const state = await store.read();
  if (!state) return { status: 'idle', message: '/fh-task 001로 첫 작업을 시작할 수 있습니다.' };
  const current = state.last?.fingerprint === fingerprint(await snapshot(store.root));
  return { task: state.task.id, active: state.active, checks: state.verificationRuns, limit: state.task.maxVerificationRuns,
    status: state.last ? (current ? state.last.status : 'stale') : 'not-checked', report: state.last?.reportPath };
}

export function conciseReport(report) {
  return JSON.stringify({ status: report.status, run: report.run, missing: report.missing,
    outsideScope: report.outsideScope, stable: report.stable, report: report.reportPath,
    steps: report.steps.map(s => ({ ...s, output: s.output?.slice(-2000) })) }, null, 2);
}
