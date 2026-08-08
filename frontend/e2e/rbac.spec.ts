import { test, expect } from "@playwright/test";
import { loginStaff } from "./_fixtures";

// Every pipeline queue lives on /staff/applications, gated per role by RoleQueues.
//
// These assertions were rewritten for the revamp: V45 deleted KYC_APPROVER and the Credit Head's
// counter-approval (the executive's sanction is final), and V48 deleted the accountant's
// disbursement hop. The old spec asserted on "Applications awaiting KYC clearance", "Credit head
// decision" and "Transfers to confirm" — three panels the product no longer has — so five of these
// tests failed against a correctly-working app.
test.describe("RBAC", () => {
  test("Credit executive gets the review queue but not the head's assign queue", async ({ page }) => {
    await loginStaff(page, "CREDIT_EXECUTIVE");
    await page.goto("/staff/applications");
    await expect(page.getByText("Credit review — accept, reject or park")).toBeVisible();
    await expect(page.getByText("Credit queue — assign an executive")).toHaveCount(0);
  });

  test("Credit head gets the assign queue", async ({ page }) => {
    await loginStaff(page, "CREDIT_HEAD");
    await page.goto("/staff/applications");
    await expect(page.getByText("Credit queue — assign an executive")).toBeVisible();
  });

  test("ADMIN sees the credit queues", async ({ page }) => {
    await loginStaff(page, "ADMIN");
    await page.goto("/staff/applications");
    await expect(page.getByText("Credit queue — assign an executive")).toBeVisible();
    await expect(page.getByText("Credit review — accept, reject or park")).toBeVisible();
  });

  test("Disbursement head gets the three release panels", async ({ page }) => {
    await loginStaff(page, "DISBURSEMENT_HEAD");
    await page.goto("/staff/applications");
    await expect(page.getByText("Pre-approved — fast-track release")).toBeVisible();
    await expect(page.getByText("Standard disbursement")).toBeVisible();
    await expect(page.getByText("Disbursement failed — retry")).toBeVisible();
  });

  /**
   * Since V48 the Accountant has NO disbursement step — the Disbursement Head's transaction id is
   * the validation. What is left is money coming back in.
   */
  test("Accountant gets the two money-in queues and no transfers queue", async ({ page }) => {
    await loginStaff(page, "ACCOUNTANT");
    await page.goto("/staff/applications");
    await expect(page.getByText("Repayments to verify")).toBeVisible();
    await expect(page.getByText("Collections payments to validate")).toBeVisible();
    await expect(page.getByText("Transfers to confirm")).toHaveCount(0);
  });

  /**
   * The same assertion on the DASHBOARD, which is a separate page with its own per-role copy.
   * Checking only /staff/applications was not enough: the retired "Transfers to confirm" wording
   * survived on the dashboard's "Your work" card for the whole of V48, and the accountant's help
   * text still described confirming a bank transfer that activates the loan — a step that role no
   * longer has. A queue can be deleted while the label pointing at it is left behind.
   */
  test("Accountant's dashboard names repayments, not the retired transfer step", async ({ page }) => {
    await loginStaff(page, "ACCOUNTANT");
    await page.goto("/staff/dashboard");
    // The label renders twice (the "Your work" card and the queue card under it) — hence .first().
    await expect(page.getByText("Repayments to verify").first()).toBeVisible();
    await expect(page.getByText("Transfers to confirm")).toHaveCount(0);
  });

  /**
   * The Disbursement Head's own help text must not promise the retired hop either: since V48 the
   * transaction id is required, so "approve without one to send it to the accountant" described a
   * path the backend now rejects with TXN_REF_REQUIRED.
   *
   * <p>The assertion is POSITIVE on purpose. That copy lives in an InfoTooltip, which renders its
   * content only while open (`{open && …}`), so a bare `toHaveCount(0)` on the stale phrase passes
   * whether the wording is right or wrong — it is absent either way. Opening the tooltip and
   * asserting what it actually says is the only form of this test that can fail.
   */
  test("Disbursement head's dashboard help text states the transaction id is required", async ({ page }) => {
    await loginStaff(page, "DISBURSEMENT_HEAD");
    await page.goto("/staff/dashboard");
    await expect(page.getByText("Approved loans to release").first()).toBeVisible();

    // hover, not click: the wrapper opens on mouseenter and the button's onClick toggles, so a
    // click (which hovers first) opens and then immediately closes it again.
    await page.getByRole("button", { name: "More information" }).first().hover();
    const tip = page.getByRole("tooltip").first();
    await expect(tip).toBeVisible();
    await expect(tip).toContainText(/transaction id is required/i);
    await expect(tip).not.toContainText(/send it to the accountant/i);
  });

  test("Accountant does not get the credit queues", async ({ page }) => {
    await loginStaff(page, "ACCOUNTANT");
    await page.goto("/staff/applications");
    await expect(page.getByText("Credit review — accept, reject or park")).toHaveCount(0);
    await expect(page.getByText("Credit queue — assign an executive")).toHaveCount(0);
  });

  /** A settlement concedes debt, so only the Collection Head sees the release queue. */
  test("Collection head gets the settlement-payment approval queue, the executive does not", async ({ page }) => {
    await loginStaff(page, "COLLECTION_HEAD");
    await page.goto("/staff/applications");
    await expect(page.getByText("Settlement payments to approve")).toBeVisible();

    await loginStaff(page, "COLLECTION_EXECUTIVE");
    await page.goto("/staff/applications");
    await expect(page.getByText("Settlement payments to approve")).toHaveCount(0);
  });

  // Collections works the same single workbench: the awaiting-repayment split + the DPD grid that
  // used to live on the removed /staff/collections/buckets page.
  test("Collection head gets the overdue/active split, the DPD buckets and an assign picker", async ({ page }) => {
    await loginStaff(page, "COLLECTION_HEAD");
    await page.goto("/staff/applications");
    await expect(page.getByRole("heading", { name: "Awaiting repayment" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Overdue", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Active", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Collections · DPD buckets" })).toBeVisible();
    // Assigning is a Head action — the picker must be on the row, not behind a second page.
    await expect(page.getByLabel("Collections executive").first()).toBeVisible();
  });

  test("Collection executive sees the queues but cannot assign", async ({ page }) => {
    await loginStaff(page, "COLLECTION_EXECUTIVE");
    await page.goto("/staff/applications");
    await expect(page.getByRole("heading", { name: "Awaiting repayment" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Collections · DPD buckets" })).toBeVisible();
    await expect(page.getByLabel("Collections executive")).toHaveCount(0);
  });

  test("non-collections role does not get the DPD buckets", async ({ page }) => {
    await loginStaff(page, "CREDIT_EXECUTIVE");
    await page.goto("/staff/applications");
    await expect(page.getByText("Collections · DPD buckets")).toHaveCount(0);
  });
});
