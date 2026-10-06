/**
 * Post-login destinations arrive in the URL (`/login?next=…`, `/staff/login?redirect=…`), so anyone
 * can craft one. Only a same-site path is honoured; anything else falls back.
 *
 * A plain `startsWith("/") && !startsWith("//")` is not enough: browsers read `\` as `/` and drop
 * tabs/newlines while parsing, so `/\evil.com` and `/\t/evil.com` both become `//evil.com`. The value
 * is therefore rejected outright if it holds a backslash or a control character, and must still
 * resolve to the same origin it was parsed against. No `window` is needed, so this is safe in render.
 */

// A reserved TLD: never a real origin, only something to resolve relative paths against.
const PARSE_BASE = "https://same-site.invalid";

// eslint-disable-next-line no-control-regex
const UNSAFE_CHARS = /[\\\u0000-\u001f\u007f]/;

/**
 * The path (+ query + hash) to navigate to, or `fallback` when `value` is missing or not a same-site
 * path. With `requiredPrefix` (e.g. `/staff/`), the path must also be inside that section.
 */
export function safeNextPath(value: string | null | undefined, fallback: string, requiredPrefix?: string): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || UNSAFE_CHARS.test(value)) {
    return fallback;
  }
  let url: URL;
  try {
    url = new URL(value, PARSE_BASE);
  } catch {
    return fallback;
  }
  if (url.origin !== PARSE_BASE) {
    return fallback;
  }
  if (requiredPrefix) {
    const section = requiredPrefix.endsWith("/") ? requiredPrefix.slice(0, -1) : requiredPrefix;
    if (url.pathname !== section && !url.pathname.startsWith(section + "/")) {
      return fallback;
    }
  }
  return url.pathname + url.search + url.hash;
}
