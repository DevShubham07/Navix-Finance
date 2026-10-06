"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { cn } from "@/lib/utils";

/**
 * The console's success/failure feedback channel. No new dependency.
 *
 * Why this exists: the console has **no** way to confirm that an action landed. Several correction
 * cards render an inline success string that never dismisses, four give no feedback at all, and the
 * entire application contains **four** `role="alert"`/`aria-live` instances — all of them form
 * validation errors. So no mutation outcome anywhere (approve, reject, verify, assign, override) is
 * announced to assistive tech, and a sighted operator often cannot tell a slow action from a
 * silent one.
 *
 * Design decisions worth not re-litigating:
 *  - **A module-level store, not context.** Mutations fire from dialogs, drawers, table rows and
 *    page bodies; threading a provider to all of them would be the whole migration. `toast.success`
 *    is importable anywhere, and `<Toaster/>` mounts once in the staff shell.
 *  - **`role="status"` + `aria-live="polite"` on a persistent container**, not on each toast. A live
 *    region must exist in the DOM *before* content is inserted for the insertion to be announced;
 *    rendering the region only when a toast appears is the classic way to get silence.
 *  - **Errors are `assertive`** and are not auto-dismissed: a failed maker-checker action is not
 *    something to miss. Successes auto-dismiss after 4s.
 *  - **Reduced motion** is honoured by the globals.css block; there is no entrance transform here
 *    beyond opacity, which is safe either way.
 *
 * This is feedback, not state. It must never be the only record that something happened — the audit
 * trail is (CLAUDE.md §5), and a toast that says "sent" must not claim more than the API confirmed.
 */
export type ToastTone = "success" | "error" | "info";

export interface ToastItem {
  id: number;
  tone: ToastTone;
  message: string;
}

type Listener = (items: ToastItem[]) => void;

const AUTO_DISMISS_MS = 4000;

let items: ToastItem[] = [];
let listeners: Listener[] = [];
let nextId = 1;

function emit() {
  // A new array identity per emit, so `useSyncExternalStore` consumers re-render.
  const snapshot = items;
  listeners.forEach((l) => l(snapshot));
}

function push(tone: ToastTone, message: string) {
  const id = nextId++;
  items = [...items, { id, tone, message }];
  emit();
  if (tone !== "error") {
    setTimeout(() => dismiss(id), AUTO_DISMISS_MS);
  }
  return id;
}

export function dismiss(id: number) {
  const next = items.filter((t) => t.id !== id);
  if (next.length === items.length) return;
  items = next;
  emit();
}

/** Test seam: drop every queued toast and reset ids. */
export function __resetToasts() {
  items = [];
  nextId = 1;
  emit();
}

export const toast = {
  success: (message: string) => push("success", message),
  error: (message: string) => push("error", message),
  info: (message: string) => push("info", message),
};

function subscribe(listener: Listener) {
  listeners = [...listeners, listener];
  return () => {
    listeners = listeners.filter((l) => l !== listener);
  };
}

const getSnapshot = () => items;
// SSR has no toasts; a stable empty array keeps `useSyncExternalStore` from looping.
const EMPTY: ToastItem[] = [];
const getServerSnapshot = () => EMPTY;

/**
 * Tints come from the 50/100 end of each scale — the `success`/`error`/`info` ramps in
 * tailwind.config.ts jump 100 -> 500, so there is no `-200`, and `info` stops at 700.
 */
const TONE_CLASS: Record<ToastTone, string> = {
  success: "border-success-100 bg-success-50 text-success-800",
  error: "border-error-100 bg-error-50 text-error-800",
  info: "border-info-100 bg-info-50 text-info-700",
};

/**
 * Mount once, in the staff shell. Renders through a portal so the region is never inside a
 * `position: sticky` cell or an `overflow` clip (the two things that have already caught the
 * Dialog and the Drawer in this codebase).
 */
export function Toaster() {
  const queue = React.useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  const [mounted, setMounted] = React.useState(false);
  React.useEffect(() => setMounted(true), []);
  if (!mounted) return null;

  return createPortal(
    <div
      // The region is always present, even when empty — see the doc comment.
      className="pointer-events-none fixed bottom-4 right-4 z-[300] flex w-[min(22rem,calc(100vw-2rem))] flex-col gap-2"
    >
      <div role="status" aria-live="polite" aria-atomic="false" className="contents">
        {queue
          .filter((t) => t.tone !== "error")
          .map((t) => (
            <ToastRow key={t.id} item={t} />
          ))}
      </div>
      <div role="alert" aria-live="assertive" aria-atomic="false" className="contents">
        {queue
          .filter((t) => t.tone === "error")
          .map((t) => (
            <ToastRow key={t.id} item={t} />
          ))}
      </div>
    </div>,
    document.body,
  );
}

function ToastRow({ item }: { item: ToastItem }) {
  return (
    <div
      className={cn(
        "pointer-events-auto flex items-start gap-2 rounded border px-3 py-2 text-xs shadow-md",
        TONE_CLASS[item.tone],
      )}
    >
      <span className="flex-1">{item.message}</span>
      <button
        type="button"
        aria-label="Dismiss"
        className="shrink-0 leading-none opacity-60 hover:opacity-100"
        onClick={() => dismiss(item.id)}
      >
        ×
      </button>
    </div>
  );
}
