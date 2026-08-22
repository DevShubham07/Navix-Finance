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
   * Cloudflare Turnstile site key (public, not a secret). Unset = no bot challenge is rendered,
   * which matches the backend skipping the check when NAVIX_CAPTCHA_SECRET is unset. Keep the two
   * in step: a site key here with no secret there is theatre; a secret there with no key here locks
   * everyone out of the password + forgot-password forms.
   */
  turnstileSiteKey: process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? "",
} as const;

export type AppConfig = typeof config;
