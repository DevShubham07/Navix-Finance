"use client";

import * as React from "react";

/**
 * Local form state that prefills from a persisted (zustand/localStorage) source
 * exactly once, after hydration. Keeping the first render equal to the server
 * markup (empty/initial) avoids hydration mismatches, then we adopt the
 * rehydrated value so Back-navigation shows what the user already entered.
 */
export function usePersistedField<T extends string | number>(
  source: T,
): [T, React.Dispatch<React.SetStateAction<T>>] {
  const [value, setValue] = React.useState<T>(source);
  const adopted = React.useRef(false);
  React.useEffect(() => {
    if (!adopted.current) {
      adopted.current = true;
      if (source !== "" && source !== 0) setValue(source);
    }
  }, [source]);
  return [value, setValue];
}
