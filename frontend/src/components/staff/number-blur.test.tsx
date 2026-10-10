import { afterEach, describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { BLUR_ATTR, BLUR_HOSTS, NumberBlur, scan } from "./number-blur";

const blurred = (el: Element | null) => el?.hasAttribute(BLUR_ATTR);

/** The demo host must show names and lists, never figures. */
describe("number blur", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("flags figures, leaves names alone, and blurs a chart's whole <text>", () => {
    document.body.innerHTML = `
      <table><tr><td id="name">Ravi Kumar</td><td id="amt">₹12,500</td><td id="dpd">31–60</td></tr></table>
      <svg><text id="tick"><tspan>4,000</tspan></text><text id="label"><tspan>Paid</tspan></text></svg>`;
    scan(document.body);
    const $ = (id: string) => document.getElementById(id);
    expect(blurred($("name"))).toBe(false);
    expect(blurred($("amt"))).toBe(true);
    expect(blurred($("dpd"))).toBe(true);
    expect(blurred($("tick"))).toBe(true);
    expect(blurred($("label"))).toBe(false);
  });

  it("follows text that changes after load, and does nothing on other hosts", async () => {
    // jsdom's host is "localhost", so the component must stay inert here…
    const { unmount } = render(
      <>
        <NumberBlur />
        <p id="p">42</p>
      </>,
    );
    expect(blurred(document.getElementById("p"))).toBe(false);
    unmount();

    // …and once the host matches, live updates are tracked both ways.
    BLUR_HOSTS.add(window.location.hostname);
    try {
      const view = render(
        <>
          <NumberBlur />
          <p id="p">Loading</p>
        </>,
      );
      const p = () => document.getElementById("p")!;
      expect(blurred(p())).toBe(false);
      p().firstChild!.nodeValue = "1,234";
      await Promise.resolve();
      expect(blurred(p())).toBe(true);
      p().firstChild!.nodeValue = "Ravi";
      await Promise.resolve();
      expect(blurred(p())).toBe(false);
      view.unmount();
    } finally {
      BLUR_HOSTS.delete(window.location.hostname);
    }
  });
});
