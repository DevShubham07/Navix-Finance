"use client";

import * as React from "react";

/**
 * The value, settled — it only updates once `value` has stopped changing for `delayMs`.
 *
 * The staff console's list pages (loans, customers, leads, …) no longer use this: they search on
 * submit via {@link SearchBar}, so typing never reaches a React Query key until Enter or the Search
 * button is pressed. The one caller left is the Cmd/Ctrl+K global-search palette
 * (`components/staff/global-search.tsx`), which is a type-ahead picker by design — it must query as
 * the operator types, so this hook debounces the *value* rather than the request, keeping the input
 * itself fully responsive (it renders from `value`) while the query waits for typing to pause.
 */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [settled, setSettled] = React.useState(value);

  React.useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return settled;
}
