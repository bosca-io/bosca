import { createRequire } from 'node:module';

const { chromium, errors } = createRequire(new URL('../bml/bml-design-catalog/package.json', import.meta.url))('playwright-core');

/** Read from the current document while allowing an in-progress navigation to replace its context. */
export async function waitForTestCondition(page, expression, { scriptsDisabled = false } = {}) {
  // Await the predicate's value: a Promise resolving false must keep polling.
  // Poll from Node so the same contract also works with page scripts disabled.
  let timer, stopped = false;
  const timeout = () => new errors.TimeoutError(`Timed out waiting for ${scriptsDisabled ? 'native HTML' : 'browser condition'}: ${expression}`);
  const poll = async () => {
    for (let attempt = 0; attempt < 120 && !stopped; attempt++) {
      try {
        const value = await page.evaluate(expression);
        if (value) return value;
      } catch (error) {
        // Navigation can replace the inspected document while its readiness read is in flight.
        if (stopped || !error.message.includes('Execution context was destroyed')) throw error;
        await page.waitForLoadState('load', { timeout: 5100 });
      }
      await new Promise(resolve => setTimeout(resolve, attempt < 20 ? 5 : 50));
    }
    throw timeout();
  };
  try {
    return await Promise.race([poll(), new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(timeout()), 5100);
    })]);
  } finally {
    stopped = true;
    clearTimeout(timer);
  }
}

/** Reap the owned browser process tree even when its control connection is unresponsive. */
export async function closeTestBrowser(server, gracePeriod = 2000) {
  let timer;
  try {
    await Promise.race([
      server.close(),
      new Promise((resolve, reject) => {
        timer = setTimeout(() => server.kill().then(resolve, reject), gracePeriod);
      }),
    ]);
  } catch (error) {
    await server.kill();
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

/** Each check owns a fresh browser, profile and page using the pinned automation defaults. */
export async function launchTestBrowser(port) {
  const server = await chromium.launchServer({
    executablePath: process.env.BML_DESIGN_CHROME_BINARY ?? chromium.executablePath(),
    headless: true,
    // The acceptance checks qualify restored history documents as well as ordinary navigation.
    ignoreDefaultArgs: ['--disable-back-forward-cache'],
    args: [`--remote-debugging-port=${port}`],
  });
  try {
    const connection = await chromium.connect(server.wsEndpoint());
    await connection.newPage();
    return {
      process: server.process(),
      connection,
      server,
      async close() {
        try { await closeTestBrowser(server); }
        finally { await connection.close(); }
      },
    };
  } catch (error) {
    await server.kill();
    throw error;
  }
}
