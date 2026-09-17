"use client";

import * as React from "react";

/**
 * The value, settled — it only updates once `value` has stopped changing for `delayMs`.
 *
 * Search boxes on the staff console feed a React Query key directly, so without this every keystroke
 * is its own request: typing a six-letter name fired six list queries, five of which were obsolete
 * before they returned. Debouncing the *value* rather than the request keeps the input itself fully
 * responsive (it renders from `value`) while the query waits for the typing to stop.
 *
 * Six pages had grown their own copy of this effect; this is that pattern, once.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [settled, setSettled] = React.useState(value);

  React.useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return settled;
}
