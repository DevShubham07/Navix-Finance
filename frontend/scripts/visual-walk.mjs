/**
 * Drives the real borrower UI end-to-end with Playwright and captures full-page
 * screenshots (desktop + mobile) of every page, then seeds each demo scenario.
 *
 * Run with the prod server live on :3000:
 *   node scripts/visual-walk.mjs
 */
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const BASE = "http://localhost:3000";
const OUT = new URL("../screenshots/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

const log = (...a) => console.log(...a);
let shotN = 0;
async function shot(page, name) {
  const file = `${OUT}${String(++shotN).padStart(2, "0")}-${name}.png`;
  await page.screenshot({ path: file, fullPage: true });
}

/** Fill a Input/Select by its placeholder or label, robustly. */
async function fillByPlaceholder(page, ph, val) {
  await page.getByPlaceholder(ph, { exact: false }).first().fill(val);
}

async function fillOtp(page, code = "123456") {
  for (let i = 0; i < code.length; i++) {
    await page.locator(`input[aria-label="Digit ${i + 1}"]`).fill(code[i]);
  }
}

/** Walk the full fresh application → repaid, screenshotting each page. */
async function freshWalk(ctx, tag) {
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  const done = [];
  const at = async (name, fn) => {
    try {
      await fn();
      await shot(page, `${tag}-${name}`);
      done.push(name);
      log(`  ✓ ${tag}/${name}`);
    } catch (e) {
      log(`  ✗ ${tag}/${name} — ${String(e.message).split("\n")[0]}`);
      await shot(page, `${tag}-${name}-ERROR`).catch(() => {});
      throw e;
    }
  };

  try {
    await at("signup-pan", async () => {
      await page.goto(`${BASE}/signup/pan`, { waitUntil: "domcontentloaded" });
      // Hide the prototype FAB for the whole SPA flow (persists across nav).
      await page.addStyleTag({ content: "[data-demobar]{display:none!important}" });
      await fillByPlaceholder(page, "Aarav Sharma", "Aarav Sharma");
      await fillByPlaceholder(page, "ABCDE1234F", "ABCPS1234A");
    });
    await at("signup-mobile", async () => {
      await page.getByRole("button", { name: "Verify & continue" }).click();
      await page.waitForURL("**/signup/mobile-otp");
      await fillByPlaceholder(page, "98765 43210", "98765 43210");
    });
    await at("signup-otp", async () => {
      await page.getByRole("button", { name: "Send code" }).click();
      await page.locator('input[aria-label="Digit 1"]').waitFor();
      await fillOtp(page);
      await page.waitForURL("**/signup/employment");
    });
    await at("signup-employment", async () => {
      await fillByPlaceholder(page, "Infosys Limited", "Infosys Limited");
      await fillByPlaceholder(page, "Senior Software Engineer", "Senior Software Engineer");
    });
    await at("signup-salary", async () => {
      await page.getByRole("button", { name: "Continue" }).click();
      await page.waitForURL("**/signup/salary");
      await fillByPlaceholder(page, "84000", "120000");
    });
    await at("signup-email", async () => {
      await page.getByRole("button", { name: "Continue" }).click();
      await page.waitForURL("**/signup/email");
      await fillByPlaceholder(page, "you@example.com", "aarav@example.com");
    });
    await at("signup-bank", async () => {
      await page.getByRole("button", { name: "Continue" }).click();
      await page.waitForURL("**/signup/bank");
      await page.getByPlaceholder("Enter your salary account number").fill("50100099887766");
      await page.getByPlaceholder("HDFC0001234").fill("HDFC0001234");
    });
    await at("signup-financials", async () => {
      await page.getByRole("button", { name: "Continue" }).click();
      await page.waitForURL("**/signup/financials");
    });
    await at("signup-coapplicant", async () => {
      await page.getByRole("button", { name: "Continue" }).first().click();
      await page.waitForURL("**/signup/co-applicant");
    });
    await at("signup-address", async () => {
      await page.getByRole("button", { name: /Continue without co-applicant|Add & continue/ }).click();
      await page.waitForURL("**/signup/address-proof");
      await page.getByPlaceholder("Flat / house, street, area").fill("12, 4th Cross, Indiranagar");
      await page.getByPlaceholder("Bengaluru").fill("Bengaluru");
      await page.getByPlaceholder("560038").fill("560038");
      await page.getByRole("button", { name: /Upload/ }).click();
    });
    await at("signup-review", async () => {
      await page.getByRole("button", { name: "Review application" }).click();
      await page.waitForURL("**/signup/review");
      await page.getByRole("checkbox").check();
    });
    await at("kyc", async () => {
      await page.getByRole("button", { name: "Submit application" }).click();
      await page.waitForURL("**/kyc");
    });
    await at("kyc-digilocker", async () => {
      await page.getByRole("link", { name: /Verify with DigiLocker|Continue/ }).click();
      await page.waitForURL("**/kyc/digilocker");
    });
    await at("kyc-callback", async () => {
      await page.getByRole("button", { name: "Continue with DigiLocker" }).click();
      await page.waitForURL("**/kyc/digilocker/callback");
      await page.getByRole("button", { name: "Continue to selfie" }).waitFor();
    });
    await at("kyc-selfie", async () => {
      await page.getByRole("button", { name: "Continue to selfie" }).click();
      await page.waitForURL("**/kyc/selfie");
    });
    await at("loan-status-review", async () => {
      await page.getByRole("button", { name: "Capture selfie" }).click();
      await page.getByRole("button", { name: "Submit for review" }).click();
      await page.waitForURL("**/loan/status");
    });
    await at("loan-status-approved", async () => {
      await page.getByRole("button", { name: "Simulate credit decision" }).click({ force: true });
      await page.getByRole("link", { name: /Choose your amount/ }).waitFor();
    });
    await at("loan-apply", async () => {
      await page.getByRole("link", { name: /Choose your amount/ }).click({ force: true });
      await page.waitForURL("**/loan/apply");
    });
    await at("loan-documents", async () => {
      await page.getByRole("button", { name: /Accept & review documents/ }).click({ force: true });
      await page.waitForURL("**/loan/documents");
      await page.getByRole("checkbox").check();
    });
    await at("loan-bank-verify", async () => {
      await page.getByRole("button", { name: "e-Sign all documents" }).click({ force: true });
      await page.waitForURL("**/loan/bank-verify");
    });
    await at("loan-disbursing", async () => {
      await page.getByRole("button", { name: "Verify account" }).click({ force: true });
      await page.getByRole("button", { name: /Receive/ }).waitFor();
    });
    await at("dashboard-active", async () => {
      await page.getByRole("button", { name: /Receive/ }).click({ force: true });
      await page.waitForURL("**/dashboard");
    });
    await at("repay", async () => {
      await page.goto(`${BASE}/repay`, { waitUntil: "domcontentloaded" });
      await page.getByPlaceholder(/4287/).fill("428712345678");
    });
    await at("repay-done", async () => {
      await page.getByRole("button", { name: /Pay ₹/ }).click({ force: true });
      await page.getByText(/repaid|closed/i).first().waitFor();
    });
  } catch {
    /* recorded above; continue to scenarios */
  }
  await page.close();
  return done;
}

/** Seed a demo scenario via the DemoBar and screenshot its landing page. */
async function scenario(ctx, label, name, tag) {
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  try {
    await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" });
    const fab = page.getByRole("button", { name: "Demo scenarios" });
    await fab.waitFor({ state: "visible" });
    await fab.click({ force: true });
    const btn = page.locator("button", { hasText: label }).first();
    await btn.waitFor({ state: "visible" });
    await btn.click({ force: true });
    await page.waitForTimeout(900);
    await page.addStyleTag({ content: "[data-demobar]{display:none!important}" });
    await shot(page, `${tag}-scenario-${name}`);
    log(`  ✓ ${tag}/scenario ${name}`);
  } catch (e) {
    log(`  ✗ ${tag}/scenario ${name} — ${String(e.message).split("\n")[0]}`);
    await shot(page, `${tag}-scenario-${name}-ERR`).catch(() => {});
  }
  await page.close();
}

const SCENARIOS = [
  ["Category A · approved", "approved-a"],
  ["Category B · under review", "review-b"],
  ["Category C · co-applicant", "coapp-c"],
  ["Category D · declined", "declined-d"],
  ["Active loan", "active"],
  ["Overdue · penalty", "overdue"],
  ["Repaid · reborrow", "repaid"],
];

const DESKTOP = { viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 };
const MOBILE = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true };
// The demo FAB is a prototype-only control; hide it during the flow walk so it
// never overlaps a primary button (and screenshots stay clean).
const hideFab = () => {
  const s = document.createElement("style");
  s.textContent = "[data-demobar]{display:none!important}";
  (document.head || document.documentElement).appendChild(s);
};

const browser = await chromium.launch();
try {
  const targets = [["desktop", DESKTOP], ["mobile", MOBILE]].filter(([t]) => !process.env.ONLY || process.env.ONLY === t);
  for (const [tag, opts] of targets) {
    log(`\n=== ${tag.toUpperCase()} full walk ===`);
    const walkCtx = await browser.newContext(opts);
    await walkCtx.addInitScript(hideFab);
    await freshWalk(walkCtx, tag);
    await walkCtx.close();

    const scnCtx = await browser.newContext(opts);
    for (const [label, name] of SCENARIOS) await scenario(scnCtx, label, name, tag);
    await scnCtx.close();
  }
} finally {
  await browser.close();
}
log(`\n=== done · ${shotN} screenshots in screenshots/ ===`);
