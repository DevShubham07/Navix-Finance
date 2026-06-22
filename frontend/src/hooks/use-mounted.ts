"use client";

import * as React from "react";

/**
 * Returns true after the first client render. Use to gate UI that depends on
 * persisted (localStorage) zustand stores so server and client markup agree
 * on the first paint, avoiding hydration mismatches.
 */
export function useMounted(): boolean {
  const [mounted, setMounted] = React.useState(false);
  React.useEffect(() => setMounted(true), []);
  return mounted;
}
