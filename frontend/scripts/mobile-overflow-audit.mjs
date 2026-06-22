/**
 * Empirical mobile-responsiveness audit. Renders every route at several narrow
 * viewport widths and reports any element that causes horizontal overflow
 * (the #1 cause of a "broken on mobile" feeling), plus tiny tap targets.
 *
 * Ground truth — measures the live DOM, not the source. Run with the prod
 * server live on :3000:
 *   npm run build && (npm start &) && node scripts/mobile-overflow-audit.mjs
 */
import { chromium } from "playwright";

const BASE = "http://localhost:3000";
const WIDTHS = [320, 360, 390, 414];
const HEIGHT = 850;

// Public + borrower routes (borrower routes aren't gated; render fallback states).
const BORROWER_ROUTES = [
  "/",
  "/login",
  "/signup/pan",
  "/signup/mobile-otp",
  "/signup/employment",
  "/signup/salary",
  "/signup/email",
  "/signup/bank",
  "/signup/financials",
  "/signup/co-applicant",
  "/signup/address-proof",
  "/signup/review",
  "/kyc",
  "/kyc/digilocker",
  "/kyc/digilocker/callback",
  "/kyc/selfie",
  "/loan/apply",
  "/loan/status",
  "/loan/documents",
  "/loan/bank-verify",
  "/dashboard",
  "/repay",
  "/reloan",
  "/profile",
];

const STAFF_ROUTES = [
  "/staff/login",
  "/staff/activate",
  "/staff/dashboard",
  "/staff/kyc-approvals",
  "/staff/credit/queue",
  "/staff/disbursement",
  "/staff/accounting",
  "/staff/collections/buckets",
  "/staff/collections/settlements",
  "/staff/admin/staff",
  "/staff/admin/invites",
  "/staff/admin/blocklist",
];

const STAFF_SESSION = {
  kind: "staff",
  userId: "staff-admin",
  name: "Meera Krishnan",
  email: "meera.krishnan@navix.finance",
  role: "ADMIN",
};
const BORROWER_SESSION = { kind: "borrower", userId: "borrower-demo", name: "Aarav Sharma", mobile: "98765 43210" };

/** Detect horizontal overflow and list the offending elements. */
const measure = `(() => {
  const winW = window.innerWidth;
  const docW = document.documentElement.scrollWidth;
  const offenders = [];
  if (docW > winW + 1) {
    const all = document.body.querySelectorAll('*');
    for (const el of all) {
      const r = el.getBoundingClientRect();
      // Element pushes past the right edge while starting inside the viewport.
      if (r.width > 0 && r.right > winW + 1 && r.left > -2 && r.left < winW) {
        // Skip if a child is the real cause (report the deepest offender only).
        const childOverflows = [...el.children].some((c) => {
          const cr = c.getBoundingClientRect();
          return cr.right > winW + 1 && cr.left > -2 && cr.left < winW;
        });
        if (childOverflows) continue;
        offenders.push({
          tag: el.tagName.toLowerCase(),
          cls: (typeof el.className === 'string' ? el.className : '').slice(0, 90),
          text: (el.textContent || '').trim().slice(0, 40),
          left: Math.round(r.left),
          right: Math.round(r.right),
          w: Math.round(r.width),
        });
      }
    }
  }
  // Tiny tap targets: interactive elements under 40px in either dimension.
  const tiny = [];
  for (const el of document.querySelectorAll('button, a[href], input, select, [role=button]')) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue; // hidden
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    if (r.height < 36 || r.width < 28) {
      const label = (el.getAttribute('aria-label') || el.textContent || el.tagName).trim().slice(0, 30);
      tiny.push({ label, w: Math.round(r.width), h: Math.round(r.height) });
    }
  }
  return { winW, docW, overflow: docW > winW + 1, offenders: offenders.slice(0, 10), tiny: tiny.slice(0, 12) };
})()`;

async function auditRoute(ctx, route, width) {
  const page = await ctx.newPage();
  page.setDefaultTimeout(15000);
  let result = { route, width, error: null };
  try {
    // domcontentloaded + fixed settle is deterministic; networkidle never
    // resolves on link-heavy pages because Next.js prefetches <Link> targets.
    const resp = await page.goto(`${BASE}${route}`, { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(900); // hydration + useMounted effect + paint
    // Hide prototype FABs so they don't false-positive on overflow/tap.
    await page.addStyleTag({ content: "[data-demobar],[data-staffbar]{display:none!important}" }).catch(() => {});
    await page.waitForTimeout(100);
    const data = await page.evaluate(measure);
    result = { route, width, status: resp?.status(), finalUrl: page.url().replace(BASE, ""), ...data };
  } catch (e) {
    result.error = String(e.message).split("\n")[0];
  }
  await page.close();
  return result;
}

async function run(label, routes, opts) {
  const ctx = await browser.newContext({ viewport: { width: WIDTHS[0], height: HEIGHT }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  if (opts.cookies) await ctx.addCookies(opts.cookies);
  if (opts.init) await ctx.addInitScript(opts.init);
  const findings = [];
  for (const route of routes) {
    for (const width of WIDTHS) {
      await ctx.pages().length; // noop
      const ctxW = await browser.newContext({
        viewport: { width, height: HEIGHT }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
      });
      if (opts.cookies) await ctxW.addCookies(opts.cookies);
      if (opts.init) await ctxW.addInitScript(opts.init);
      const r = await auditRoute(ctxW, route, width);
      await ctxW.close();
      if (r.error) console.log(`  ⚠︎  ${route} @${width}  ERROR ${r.error}`);
      else if (r.overflow) {
        console.log(`  ✗  ${route} @${width}  OVERFLOW docW=${r.docW} > ${r.winW}`);
        for (const o of r.offenders) console.log(`        <${o.tag} class="${o.cls}"> w=${o.w} right=${o.right}  "${o.text}"`);
        findings.push(r);
      } else {
        const tinyNote = r.tiny?.length ? `  (tap<36px: ${r.tiny.length})` : "";
        console.log(`  ✓  ${route} @${width}  ok${tinyNote}`);
      }
      if (r.tiny?.length) for (const t of r.tiny) if (t.h < 32) console.log(`        tiny-tap "${t.label}" ${t.w}x${t.h}`);
    }
  }
  await ctx.close();
  return findings;
}

const browser = await chromium.launch();
const allFindings = [];
try {
  console.log("\n=== BORROWER / PUBLIC ROUTES ===");
  allFindings.push(...await run("borrower", BORROWER_ROUTES, {
    init: () => {
      try { localStorage.setItem("navix.borrower.session", JSON.stringify({ kind: "borrower", userId: "borrower-demo", name: "Aarav Sharma", mobile: "98765 43210" })); } catch {}
    },
  }));

  console.log("\n=== STAFF ROUTES (signed in as Admin) ===");
  allFindings.push(...await run("staff", STAFF_ROUTES, {
    cookies: [{ name: "navix_session", value: "staff-admin", url: BASE }],
    init: () => {
      try { localStorage.setItem("navix.staff.session", JSON.stringify({ kind: "staff", userId: "staff-admin", name: "Meera Krishnan", email: "meera.krishnan@navix.finance", role: "ADMIN" })); } catch {}
    },
  }));
} finally {
  await browser.close();
}

console.log(`\n=== SUMMARY: ${allFindings.length} (route×width) overflow findings ===`);
const byRoute = {};
for (const f of allFindings) (byRoute[f.route] ||= []).push(f.width);
for (const [route, widths] of Object.entries(byRoute)) console.log(`  ${route} → overflows at ${widths.join(", ")}px`);
if (!allFindings.length) console.log("  none — no horizontal overflow on any route 🎉");
