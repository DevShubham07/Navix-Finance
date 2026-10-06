/**
 * Setup for the `dom` vitest project (component tests, `*.test.tsx`).
 *
 * Adds `@testing-library/jest-dom`'s matchers (`toBeInTheDocument`, `toHaveClass`, …) and stubs the
 * two browser APIs the console's components touch that jsdom does not implement:
 *
 *  - `matchMedia` — read by the reduced-motion checks the Phase 1 motion work introduces.
 *  - `ResizeObserver` — constructed by Recharts' `ResponsiveContainer` (staff performance page) and
 *    by the tooltip/popover positioning in `ui/tooltip.tsx`.
 *
 * Both are deliberately inert rather than clever: a test that cares about resize or media state
 * should assert on its own stub, not inherit behaviour from here.
 */
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";

/**
 * Unmount every rendered tree between tests.
 *
 * Testing Library registers this itself ONLY when it can see a global `afterEach`, i.e. when
 * vitest runs with `globals: true`. This project does not enable globals, so without an explicit
 * registration each `render()` appends to the same document and tests leak into one another — a
 * later "this element should not exist" assertion then fails because an *earlier* test rendered
 * it. Registering it here rather than per-file means a new test file cannot forget.
 */
afterEach(cleanup);

if (!window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

/**
 * `offsetParent`, which jsdom hardcodes to `null` because it implements no layout engine.
 *
 * This is load-bearing for the focus trap: `useFocusTrap` filters candidates with
 * `el.offsetParent !== null` to skip elements hidden by CSS, so under unpatched jsdom *every*
 * candidate looks hidden, the trap finds nothing focusable and falls back to focusing the panel
 * itself. Weakening the hook to suit the test environment would be the wrong trade — the filter is
 * correct in a real browser — so the environment is shimmed instead.
 *
 * The shim is intentionally crude but honest about the one case the filter cares about: an element
 * is treated as laid out unless it or an ancestor is `display: none` or carries `hidden`. It does
 * not model `visibility`, clipping or zero-size boxes; a test that depends on those should assert
 * on its own stub.
 */
function fakeOffsetParent(el: HTMLElement): Element | null {
  for (let node: HTMLElement | null = el; node; node = node.parentElement) {
    if (node.hasAttribute("hidden")) return null;
    if (node.style?.display === "none") return null;
  }
  return el.parentElement ?? document.body;
}

Object.defineProperty(HTMLElement.prototype, "offsetParent", {
  configurable: true,
  get(): Element | null {
    return fakeOffsetParent(this as HTMLElement);
  },
});

if (!("ResizeObserver" in globalThis)) {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub;
}
