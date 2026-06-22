/**
 * Captures the high-value POPULATED mobile screens (390px) that the cold
 * overflow audit can't exercise — real loan data, the amount chooser, the
 * overdue-penalty repay screen, and a staff credit-review detail page. These
 * are the screens most likely to overflow with real numbers/tables.
 *
 *   node scripts/mobile-shots.mjs    (prod server live on :3000)
 */
import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const BASE = "http://localhost:3000";
const OUT = new URL("../screenshots/mobile/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const MOBILE = { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true };
const hideFab = () => {
  const s = document.createElement("style");
  s.textContent = "[data-demobar],[data-staffbar]{display:none!important}";
  (document.head || document.documentElement).appendChild(s);
};
let n = 0;
const HIDE_FAB = "[data-demobar],[data-staffbar]{display:none!important}";
const shot = async (page, name) => {
  await page.waitForTimeout(900); // hydration + useMounted effect + paint
  await page.addStyleTag({ content: HIDE_FAB }).catch(() => {}); // hide prototype FABs in the shot only
  await page.screenshot({ path: `${OUT}${String(++n).padStart(2, "0")}-${name}.png`, fullPage: true });
};

async function seedBorrower(ctx, label) {
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" });
  const fab = page.getByRole("button", { name: "Demo scenarios" });
  await fab.waitFor({ state: "visible" });
  await fab.click({ force: true });
  await page.locator("button", { hasText: label }).first().click({ force: true });
  await page.waitForTimeout(800);
  return page;
}

const browser = await chromium.launch();
try {
  // ---- Borrower populated states ----
  const bctx = await browser.newContext(MOBILE);

  let page = await seedBorrower(bctx, "Active loan");
  await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" }); await shot(page, "dashboard-active-loan");
  await page.goto(`${BASE}/repay`, { waitUntil: "domcontentloaded" }); await shot(page, "repay-active");
  await page.close();

  page = await seedBorrower(bctx, "Overdue · penalty");
  await page.goto(`${BASE}/repay`, { waitUntil: "domcontentloaded" }); await shot(page, "repay-overdue-penalty");
  await page.goto(`${BASE}/dashboard`, { waitUntil: "domcontentloaded" }); await shot(page, "dashboard-overdue");
  await page.close();

  page = await seedBorrower(bctx, "Category A · approved");
  await page.goto(`${BASE}/loan/apply`, { waitUntil: "domcontentloaded" }); await shot(page, "loan-apply-amount-chooser");
  await page.goto(`${BASE}/loan/documents`, { waitUntil: "domcontentloaded" }); await shot(page, "loan-documents");
  await page.close();

  page = await seedBorrower(bctx, "Category C · co-applicant");
  await page.goto(`${BASE}/loan/status`, { waitUntil: "domcontentloaded" }); await shot(page, "loan-status-coapplicant");
  await page.close();
  await bctx.close();

  // ---- Staff populated states (signed in as Admin) ----
  const sctx = await browser.newContext(MOBILE);
  await sctx.addCookies([{ name: "navix_session", value: "staff-admin", url: BASE }]);
  await sctx.addInitScript(() => {
    try { localStorage.setItem("navix.staff.session", JSON.stringify({ kind: "staff", userId: "staff-admin", name: "Meera Krishnan", email: "meera.krishnan@navix.finance", role: "ADMIN" })); } catch {}
  });
  page = await sctx.newPage();
  await page.goto(`${BASE}/staff/dashboard`, { waitUntil: "domcontentloaded" }); await shot(page, "staff-dashboard");
  await page.goto(`${BASE}/staff/kyc-approvals`, { waitUntil: "domcontentloaded" }); await shot(page, "staff-kyc-approvals");
  // Open the first credit-review detail page (real application data).
  const creditLink = await page.locator('a[href^="/staff/credit/"]').first();
  if (await creditLink.count()) {
    const href = await creditLink.getAttribute("href");
    await page.goto(`${BASE}${href}`, { waitUntil: "domcontentloaded" });
    await shot(page, "staff-credit-detail");
  }
  await page.close();
  await sctx.close();
} finally {
  await browser.close();
}
console.log(`done · ${n} mobile screenshots in screenshots/mobile/`);
