import { defineConfig } from '@playwright/test';

// One server for all the tests, so they run one after another; generating a town takes minutes
export default defineConfig({
  testDir: './cases',
  workers: 1,
  timeout: 15 * 60 * 1000,
  ...(process.env.AUTOTEST_RESULT_DIR ? {outputDir:process.env.AUTOTEST_RESULT_DIR+'/playwright'} : {}),
  reporter: process.env.AUTOTEST_RESULT_DIR
    ? [['list'], ['json',{outputFile:process.env.AUTOTEST_RESULT_DIR+'/tests.json'}]]
    : [['list']],
});
