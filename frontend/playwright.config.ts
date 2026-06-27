import { defineConfig, devices } from "@playwright/test";

/**
 * E2E config for NAVIX Finance. Runs against an ALREADY-RUNNING stack
 * (frontend :3000 → BFF → backend :8080 with real JWT auth + NAVIX_SMS_DEV_ECHO=true),
 * so `webServer` is intentionally omitted. Uses the cached Playwright Chromium
 * (no `playwright install` needed) with fake media/geolocation for the camera +
 * geo onboarding steps. Serial (workers: 1) since specs share one live backend.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 8_000 },
  reporter: "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    permissions: ["geolocation"],
    geolocation: { latitude: 28.61, longitude: 77.23 },
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        channel: undefined, // use the bundled, cached Chromium
        launchOptions: {
          args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"],
        },
      },
    },
  ],
});
