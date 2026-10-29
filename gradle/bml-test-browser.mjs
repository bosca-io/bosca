import { createRequire } from 'node:module';

const { chromium } = createRequire(new URL('../bml/bml-design-catalog/package.json', import.meta.url))('playwright-core');

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
