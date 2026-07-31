import { test, expect } from "@playwright/test";
import { loginStaff } from "./_fixtures";

// Every pipeline queue now lives on /staff/applications, gated per role by RoleQueues. The KYC
// approver holds loan:review/loan:approve only for the instant-loan fast-path, so it must NOT get
// the credit exec→head panels — every action there is rejected by the backend anyway.
test.describe("RBAC", () => {
  test("KYC approver does not get the credit maker-checker panels", async ({ page }) => {
    await loginStaff(page, "KYC_APPROVER");
    await page.goto("/staff/applications");
    await expect(page.getByText("Applications awaiting KYC clearance")).toBeVisible();
    await expect(page.getByText("Credit head decision")).toHaveCount(0);
    await expect(page.getByText("Credit queue — assign an executive")).toHaveCount(0);
  });

  test("ADMIN sees the credit-head queue", async ({ page }) => {
    await loginStaff(page, "ADMIN");
    await page.goto("/staff/applications");
    await expect(page.getByText("Credit head decision")).toBeVisible();
  });

  test("Disbursement head gets the three release panels", async ({ page }) => {
    await loginStaff(page, "DISBURSEMENT_HEAD");
    await page.goto("/staff/applications");
    await expect(page.getByText("Pre-approved — fast-track release")).toBeVisible();
    await expect(page.getByText("Standard disbursement")).toBeVisible();
    await expect(page.getByText("Disbursement failed — retry")).toBeVisible();
  });

  test("Accountant gets transfers + the repayment-verify queue", async ({ page }) => {
    await loginStaff(page, "ACCOUNTANT");
    await page.goto("/staff/applications");
    await expect(page.getByText("Transfers to confirm")).toBeVisible();
    await expect(page.getByText("Repayments to verify")).toBeVisible();
  });

  // KYC approvals now live on /staff/applications (the single per-role workbench).
  test("non-KYC role does not get the KYC clearance queue", async ({ page }) => {
    await loginStaff(page, "ACCOUNTANT");
    await page.goto("/staff/applications");
    await expect(page.getByText("Applications awaiting KYC clearance")).toHaveCount(0);
  });

  test("KYC approver sees the KYC clearance queue", async ({ page }) => {
    await loginStaff(page, "KYC_APPROVER");
    await page.goto("/staff/applications");
    await expect(page.getByText("Applications awaiting KYC clearance")).toBeVisible();
  });

  test("KYC approver sees the instant-loan credit fast-path", async ({ page }) => {
    await loginStaff(page, "KYC_APPROVER");
    await page.goto("/staff/applications");
    await expect(page.getByText("Approve instant loans (credit clearance)")).toBeVisible();
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
