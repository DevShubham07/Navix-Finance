import path from "node:path";
import { defineWorkspace } from "vitest/config";

const alias = { "@": path.resolve(__dirname, "./src") };

/**
 * Two projects, because the suite has two kinds of test with incompatible needs.
 *
 * `node` — the pure-function tests under `src/lib/**`. They were the whole suite before Phase 1.0
 * (`environment: "node"`, `include: ["src/**\/*.test.ts"]`) and they stay on a node environment:
 * no DOM, no setup file, nothing to slow them down.
 *
 * `dom`  — component tests (`*.test.tsx`), which need jsdom plus `@testing-library/jest-dom`'s
 * matchers. This is why a `.tsx` test was previously not merely unsupported but *uncollected*: the
 * single `include` glob ended in `.test.ts`, which does not match `.test.tsx`.
 *
 * Separate projects rather than one jsdom environment for everything: the lib suites do not pay
 * jsdom's startup cost, and a DOM-only global cannot leak into a pure-function test and hide a
 * real dependency on it.
 */
export default defineWorkspace([
  {
    resolve: { alias },
    test: {
      name: "node",
      environment: "node",
      include: ["src/**/*.test.ts"],
    },
  },
  {
    resolve: { alias },
    // The project has no `@vitejs/plugin-react` (Next compiles the app itself, and tsconfig sets
    // `"jsx": "preserve"` so the bundler decides). esbuild's default is the *classic* transform,
    // which emits `React.createElement` and fails with "React is not defined" in files that follow
    // the codebase's convention of not importing React in a test. Opt into the automatic runtime.
    esbuild: { jsx: "automatic" },
    test: {
      name: "dom",
      environment: "jsdom",
      include: ["src/**/*.test.tsx"],
      setupFiles: ["./vitest.setup.ts"],
      restoreMocks: true,
    },
  },
]);
