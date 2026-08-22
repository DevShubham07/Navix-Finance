"use client";

import { useEffect, useRef } from "react";
import Script from "next/script";
import { config } from "@/lib/config";

/**
 * Cloudflare Turnstile — the bot challenge in front of the password logins and both
 * forgot-password forms (the backend counterpart is `CaptchaVerifier`).
 *
 * Renders nothing when `NEXT_PUBLIC_TURNSTILE_SITE_KEY` is unset — the same env-gated pattern as
 * `<GoogleAnalytics />`, and what keeps local dev, the Playwright suite and the demo stack working
 * with no configuration. Pages must therefore gate their submit button on
 * `!config.turnstileSiteKey || !!token`, never on the token alone.
 *
 * Usually invisible: most visitors are cleared without ever seeing a checkbox, so `onToken` may
 * fire within a second of mount.
 *
 * A token is SINGLE-USE. Bump `resetKey` after any failed submit or the retry re-sends a token the
 * provider has already burned, and the second attempt fails no matter what the user types.
 *
 * `onError` fires when the widget cannot produce a token at all — the commonest cause is the site
 * key's domain allowlist not covering the origin it is being served from. Callers MUST use it to
 * stop blocking submit: a dead button with no message is a worse failure than letting the request
 * through for the backend to judge (it answers CAPTCHA_FAILED, which the user can actually read).
 *
 * Needs `challenges.cloudflare.com` in script-src, frame-src AND connect-src — see next.config.mjs.
 */

interface TurnstileApi {
  render: (
    el: HTMLElement,
    opts: {
      sitekey: string;
      action?: string;
      callback: (token: string) => void;
      "expired-callback"?: () => void;
      "error-callback"?: () => void;
      theme?: "light" | "dark" | "auto";
    },
  ) => string;
  remove: (widgetId: string) => void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

export function Turnstile({
  action,
  onToken,
  onError,
  resetKey = 0,
}: {
  /**
   * The surface this challenge belongs to. Cloudflare echoes it back to siteverify and the backend
   * requires an exact match, so it MUST equal the `ACTION_*` constant for the matching endpoint in
   * `AuthController` — otherwise every submit on this page fails with CAPTCHA_FAILED.
   */
  action: string;
  /** Called with a fresh token, and with "" whenever the current one expires or errors. */
  onToken: (token: string) => void;
  /** The widget could not run at all — unblock submit rather than stranding the user. */
  onError?: () => void;
  /** Change this to force a new challenge (the previous token is spent). */
  resetKey?: number;
}) {
  const siteKey = config.turnstileSiteKey;
  const holder = useRef<HTMLDivElement>(null);
  // The callback identity changes on every parent render; keeping it in a ref means the widget is
  // mounted once per resetKey instead of being torn down and re-rendered mid-challenge.
  const cb = useRef(onToken);
  cb.current = onToken;
  const errCb = useRef(onError);
  errCb.current = onError;

  useEffect(() => {
    if (!siteKey) return;
    let widgetId: string | undefined;
    let cancelled = false;

    // api.js may still be loading when this runs (next/script is async), so poll briefly for it
    // rather than racing it with an onLoad handler that can fire before this effect.
    const tick = window.setInterval(() => {
      const el = holder.current;
      if (cancelled || !window.turnstile || !el || widgetId) return;
      window.clearInterval(tick);
      el.replaceChildren();
      widgetId = window.turnstile.render(el, {
        sitekey: siteKey,
        action,
        callback: (token) => cb.current(token),
        "expired-callback": () => cb.current(""),
        "error-callback": () => {
          cb.current("");
          errCb.current?.();
        },
      });
    }, 100);

    return () => {
      cancelled = true;
      window.clearInterval(tick);
      if (widgetId) window.turnstile?.remove(widgetId);
    };
  }, [siteKey, action, resetKey]);

  if (!siteKey) return null;

  return (
    <>
      <Script
        src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"
        strategy="afterInteractive"
      />
      <div ref={holder} className="mb-4" />
    </>
  );
}
