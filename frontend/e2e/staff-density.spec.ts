import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import path from "node:path";

const globalStyles = path.resolve(process.cwd(), "src/app/globals.css");

async function mountFixture(page: Page) {
  await page.setContent(`
    <html class="staff-desktop-density">
      <body>
        <header><button data-testid="toolbar-button" class="btn btn-sm">Toolbar action</button></header>
        <main class="navix-crm">
          <div data-testid="table-scroll" style="width: 1000px; overflow-x: auto">
            <table class="staff-data-table">
              <thead><tr><th>Application</th><th>Customer details and assignment</th><th>Actions</th></tr></thead>
              <tbody><tr><td>#10001</td><td>Customer details</td><td><button class="btn btn-sm">Open customer journey</button></td></tr></tbody>
            </table>
          </div>
        </main>
      </body>
  `);
  await page.addStyleTag({ content: ".navix-crm table.staff-data-table { min-width: 1120px; }" });
  await page.addStyleTag({ path: globalStyles });
}

test("staff tables and buttons compact without desktop horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 });
  await mountFixture(page);

  await expect.poll(() => page.locator("html").evaluate((el) => getComputedStyle(el).fontSize)).toBe("12.8px");
  const overflow = await page.getByTestId("table-scroll").evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await expect.poll(() => page.getByTestId("toolbar-button").evaluate((el) => getComputedStyle(el).padding)).toBe("8px 12px");
});

test("mobile staff sizing and wide-table scrolling remain unchanged", async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 768 });
  await mountFixture(page);

  await expect.poll(() => page.locator("html").evaluate((el) => getComputedStyle(el).fontSize)).toBe("16px");
  const overflow = await page.getByTestId("table-scroll").evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(overflow).toBeGreaterThan(1);
});
