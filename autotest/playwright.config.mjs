import { defineConfig } from '@playwright/test';

// One server for all the tests, so they run one after another; generating a town takes minutes
export default defineConfig({
  testDir: './cases',
  workers: 1,
  timeout: 15 * 60 * 1000,
  reporter: [['list']],
});
