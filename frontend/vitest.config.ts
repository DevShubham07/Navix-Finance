import path from "node:path";
import { defineConfig } from "vitest/config";

/**
 * Shared resolution for both vitest projects (see `vitest.workspace.ts` for the split).
 *
 * The `@` alias has to be repeated per project in the workspace file — a workspace project does
 * not inherit `resolve` from here — so this config exists for the alias when vitest is invoked
 * directly on a single file, and to keep one place to change the alias.
 */
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
});
