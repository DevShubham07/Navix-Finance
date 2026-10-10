import { afterEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import { BLUR_ATTR, BLUR_HOSTS, CHART_ATTR, NumberBlur, isAnalyticsRoute, scan } from "./number-blur";

let pathname = "/staff/customers";
vi.mock("next/navigation", () => ({ usePathname: () => pathname }));

const blurred = (el: Element | null) => el?.hasAttribute(BLUR_ATTR);
const $ = (id: string) => document.getElementById(id);

/** The demo host hides the scale of the business, never a customer's own details. */
describe("number blur", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("on a register page hides only marked aggregates; customer rows stay readable", () => {
    document.body.innerHTML = `
      <main>
        <table><tr><td id="name">Ravi Kumar</td><td id="mobile">9876543210</td><td id="amt">₹12,500</td></tr></table>
        <span id="showing">Showing 1–25 of <span id="total" data-redact>1,204</span></span>
      </main>`;
    scan(document.body, false);
    expect(blurred($("name"))).toBe(false);
    expect(blurred($("mobile"))).toBe(false);
    expect(blurred($("amt"))).toBe(false);
    expect(blurred($("showing"))).toBe(false);
    expect(blurred($("total"))).toBe(true);
  });

  it("on an analytics page hides every figure in <main> and frosts charts, but not portals", () => {
    document.body.innerHTML = `
      <main>
        <p id="kpi">₹4.2 Cr</p><p id="label">Disbursed this month</p>
        <div class="recharts-wrapper" id="chart"><svg><text id="tick"><tspan>4,000</tspan></text></svg></div>
      </main>
      <div role="dialog"><span id="drawer-amt">₹12,500</span></div>`;
    scan(document.body, true);
    expect(blurred($("kpi"))).toBe(true);
    expect(blurred($("label"))).toBe(false);
    expect($("chart")?.hasAttribute(CHART_ATTR)).toBe(true);
    expect(blurred($("tick"))).toBe(false); // the chart is frosted whole instead
    expect(blurred($("drawer-amt"))).toBe(false);
  });

  it("knows its analytics routes", () => {
    expect(isAnalyticsRoute("/staff/dashboard")).toBe(true);
    expect(isAnalyticsRoute("/staff/admin/expenses")).toBe(true);
    expect(isAnalyticsRoute("/staff/customers")).toBe(false);
    expect(isAnalyticsRoute("/staff/dashboards-x")).toBe(false);
  });

  it("follows live updates, and does nothing on other hosts", async () => {
    pathname = "/staff/dashboard";
    const tree = (
      <>
        <NumberBlur />
        <main>
          <p id="p">Loading</p>
        </main>
      </>
    );
    // jsdom's host is "localhost", so the component must stay inert here…
    const first = render(tree);
    $("p")!.firstChild!.nodeValue = "42";
    await Promise.resolve();
    expect(blurred($("p"))).toBe(false);
    first.unmount();

    // …and once the host matches, live updates are tracked both ways.
    BLUR_HOSTS.add(window.location.hostname);
    try {
      const view = render(tree);
      $("p")!.firstChild!.nodeValue = "1,234";
      await Promise.resolve();
      expect(blurred($("p"))).toBe(true);
      $("p")!.firstChild!.nodeValue = "Ravi";
      await Promise.resolve();
      expect(blurred($("p"))).toBe(false);
      view.unmount();
    } finally {
      BLUR_HOSTS.delete(window.location.hostname);
    }
  });
});
