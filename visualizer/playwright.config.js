import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';
const localChrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const executablePath = process.env.FOLIA_CHROME_PATH || (existsSync(localChrome) ? localChrome : undefined);
export default defineConfig({
  testDir: './browser-test', workers: 1, timeout: 60000,
  use: { viewport: { width: 390, height: 440 }, deviceScaleFactor: 2,
    launchOptions: { executablePath,
      args: ['--enable-webgl', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] } },
});
