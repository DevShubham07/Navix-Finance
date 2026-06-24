/**
 * Typed access to public/server runtime configuration.
 *
 * NEXT_PUBLIC_API_BASE_URL is exposed to the browser (used by client components
 * to reach the Next.js BFF). BACKEND_BASE_URL is server-only (used by route
 * handlers to reach the Spring Boot backend at http://localhost:8080).
 */

export const config = {
  /** Browser-visible base URL for the Next.js BFF (defaults to same origin). */
  apiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:3000/api",
  /** Server-only base URL for the Spring Boot backend. */
  backendBaseUrl: process.env.BACKEND_BASE_URL ?? "http://localhost:8080",
  /**
   * Demo mode: when on, the app runs entirely on local mock data (Zustand
   * stores) and shows the floating DemoBar. Keep this ON while the real
   * backend is still being built. Set NEXT_PUBLIC_DEMO_MODE=false to switch
   * the UI over to real API calls.
   *
   * Defaults to ON so a missing env var never silently disables the only
   * working data layer.
   */
  demoMode: process.env.NEXT_PUBLIC_DEMO_MODE !== "false",
} as const;

export type AppConfig = typeof config;
