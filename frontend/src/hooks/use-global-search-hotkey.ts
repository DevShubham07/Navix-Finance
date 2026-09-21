"use client";

import * as React from "react";

/**
 * Cmd/Ctrl+K opens the staff global-search palette.
 *
 * <p>Listens in the CAPTURE phase so the shortcut still fires when focus is inside a page's own
 * search box, and so `preventDefault` beats the browser's own Ctrl+K (address-bar search in
 * Chrome/Firefox) while the page has focus.
 *
 * <p>The shortcut is deliberately inert while another modal is open: the staff queues open detail
 * dialogs constantly, and a palette stacked on top of a half-finished maker-checker decision is a
 * way to lose work, not a shortcut.
 */
export function useGlobalSearchHotkey(onOpen: () => void, enabled = true): { isMac: boolean } {
  const [isMac, setIsMac] = React.useState(false);

  // Read the platform after mount — `navigator` does not exist during SSR, and branching on it
  // during render would mismatch the server's HTML.
  React.useEffect(() => {
    setIsMac(/Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent));
  }, []);

  React.useEffect(() => {
    if (!enabled) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.isComposing) return;
      if (!(e.metaKey || e.ctrlKey) || e.altKey || e.shiftKey) return;
      if (e.key.toLowerCase() !== "k") return;
      // Another dialog/drawer owns the screen — leave it alone.
      if (document.querySelector('[role="dialog"]')) return;
      e.preventDefault();
      e.stopPropagation();
      onOpen();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, [onOpen, enabled]);

  return { isMac };
}
