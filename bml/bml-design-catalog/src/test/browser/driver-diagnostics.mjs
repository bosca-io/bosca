import { execFile } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { promisify } from 'node:util';

const execute = promisify(execFile);
const failure = error => ({ name: error.name, message: error.message });
const brief = value => {
  const json = JSON.stringify(value);
  return json?.length > 3000 ? { length: json.length, start: json.slice(0, 1500), end: json.slice(-1500) } : value;
};

/** Evidence comes from the owned fixture; diagnostics never retry a failed acceptance operation. */
export function driverDiagnostics({ browser, cdp, artifacts, mode, call }) {
  const operations = [], events = [], scriptUrls = new Map();
  let next = 0, active, pauseReceived;
  const push = (list, value) => { list.push(value); if (list.length > 60) list.shift(); };
  const diagnostic = {
    sent(id, method, params) {
      const entry = { id, method, params: brief(params), started: performance.now(), state: 'pending' };
      push(operations, entry); active = entry;
    },
    received(id, error) {
      const entry = operations.find(item => item.id === id);
      if (entry) Object.assign(entry, { state: entry.state === 'timeout' ? 'late-response' : error ? 'error' : 'completed', elapsed: performance.now() - entry.started, error });
    },
    timedOut(id) {
      const entry = operations.find(item => item.id === id);
      if (entry) Object.assign(entry, { state: 'timeout', elapsed: performance.now() - entry.started });
    },
    event(message) {
      if (message.method === 'Runtime.executionContextsCleared') scriptUrls.clear();
      if (message.method === 'Debugger.scriptParsed' && message.params.url) scriptUrls.set(message.params.scriptId, message.params.url);
      if (message.method === 'Debugger.paused') pauseReceived?.(message.params);
      if (['Page.frameNavigated', 'Inspector.detached', 'Inspector.targetCrashed', 'Runtime.executionContextsCleared', 'Debugger.paused'].includes(message.method))
        push(events, { method: message.method, params: brief(message.params), at: performance.now() });
    },
    async capture(error) {
      const report = { error: failure(error), failedOperation: active, operations: structuredClone(operations), events: structuredClone(events),
        browser: { pid: browser.pid, exitCode: browser.exitCode, signalCode: browser.signalCode },
        node: { pid: process.pid, memory: process.memoryUsage(), resources: process.resourceUsage() }, probes: {} };
      const path = join(artifacts, `catalog-${mode}-driver-failure-${Date.now()}-${++next}.json`);
      const save = () => writeFile(path, JSON.stringify(report, null, 2));
      // Save before probing so renderer or transport failure cannot erase the original request history.
      await save();
      console.error(`Catalog ${mode} failure evidence: ${path}`);
      try {
        const result = await execute('ps', ['-axo', 'pid=,ppid=,stat=,pcpu=,rss=,etime=,comm='], { timeout: 2000, maxBuffer: 4 * 1024 * 1024 });
        const rows = result.stdout.trim().split('\n').map(line => {
          const match = line.trim().match(/^(\d+)\s+(\d+)\s+(\S+)\s+([\d.]+)\s+(\d+)\s+(\S+)\s+(.+)$/);
          return match ? { pid: Number(match[1]), parent: Number(match[2]), state: match[3], cpu: Number(match[4]), rssKiB: Number(match[5]), elapsed: match[6], executable: match[7] } : null;
        }).filter(Boolean);
        const owned = new Set([browser.pid]);
        let previous = 0;
        while (previous !== owned.size) { previous = owned.size; for (const row of rows) if (owned.has(row.parent)) owned.add(row.pid); }
        report.processes = rows.filter(row => owned.has(row.pid));
      } catch (error) { report.processError = failure(error); }
      let socket;
      try {
        const version = await (await fetch(`${cdp}/json/version`, { signal: AbortSignal.timeout(2000) })).json();
        report.probes.httpVersion = version;
        report.probes.targets = await (await fetch(`${cdp}/json/list`, { signal: AbortSignal.timeout(2000) })).json();
        socket = new WebSocket(version.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('Diagnostic browser socket did not open.')), 2000);
          socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
          socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('Diagnostic browser socket failed.')); }, { once: true });
        });
        let id = 0;
        const request = (method, params = {}) => new Promise((resolve, reject) => {
          const sequence = ++id;
          const receive = event => {
            const response = JSON.parse(event.data);
            if (response.id !== sequence) return;
            clearTimeout(timer); socket.removeEventListener('message', receive);
            if (response.error) reject(new Error(JSON.stringify(response.error))); else resolve(response.result);
          };
          const timer = setTimeout(() => { socket.removeEventListener('message', receive); reject(new Error(`Diagnostic browser request timed out: ${method}`)); }, 2000);
          socket.addEventListener('message', receive); socket.send(JSON.stringify({ id: sequence, method, params }));
        });
        report.probes.browser = await request('Browser.getVersion');
        report.probes.processes = await request('SystemInfo.getProcessInfo');
      } catch (error) { report.probes.browserError = failure(error); }
      finally { socket?.close(); }
      await save();
      try {
        report.probes.renderer = await call('Runtime.evaluate', { expression: '({url:location.href,ready:document.readyState,active:document.activeElement?.outerHTML.slice(0,1000),scroll:{x:scrollX,y:scrollY}})', returnByValue: true }, 2000);
      } catch (error) { report.probes.rendererError = failure(error); }
      await save();
      // Sample the busiest owned renderer before interrupting it; bound diagnostic work as well.
      const cpu = pid => report.processes?.find(row => row.pid === pid)?.cpu ?? 0;
      const renderers = (report.probes.processes?.processInfo ?? []).filter(item => item.type === 'renderer').sort((a, b) => cpu(b.id) - cpu(a.id));
      if (process.platform === 'darwin' && report.probes.rendererError) for (const processInfo of renderers.slice(0, 1)) {
        if (!report.processes?.some(row => row.pid === processInfo.id)) continue;
        const sample = join(artifacts, `catalog-${mode}-renderer-${processInfo.id}-${Date.now()}.sample.txt`);
        try {
          await execute('/usr/bin/sample', [String(processInfo.id), '1', '1', '-file', sample], { timeout: 4000 });
          (report.samples ??= []).push(sample);
        } catch (error) { (report.sampleErrors ??= []).push({ pid: processInfo.id, ...failure(error) }); }
      }
      if (report.probes.rendererError) {
        const pauseEvent = new Promise(resolve => {
          const timer = setTimeout(() => { pauseReceived = undefined; resolve(null); }, 2000);
          pauseReceived = value => { clearTimeout(timer); pauseReceived = undefined; resolve(value); };
        });
        try {
          await call('Debugger.pause', {}, 2000);
          // The acknowledgement and paused event arrive separately. Store frames, never local values.
          const paused = await pauseEvent;
          report.probes.paused = paused ? { reason: paused.reason, frames: paused.callFrames.map(frame => ({ functionName: frame.functionName,
            url: frame.url || scriptUrls.get(frame.location.scriptId) || "", location: frame.location })) } : null;
        } catch (error) { report.probes.debuggerError = failure(error); }
      }
      report.followupOperations = structuredClone(operations);
      await save();
      const navigation = report.events.findLast(event => event.method === 'Page.frameNavigated');
      console.error(`Catalog ${mode} driver evidence: ${JSON.stringify({
        error: report.error,
        failedOperation: report.failedOperation,
        page: navigation?.params?.frame?.url,
        renderer: report.probes.renderer,
        rendererError: report.probes.rendererError,
        paused: report.probes.paused,
        debuggerError: report.probes.debuggerError,
        processes: report.processes,
      })}`);
      return path;
    },
  };
  return diagnostic;
}
