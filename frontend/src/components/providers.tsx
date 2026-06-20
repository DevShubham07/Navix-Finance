"use client";

import * as React from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { makeQueryClient } from "@/lib/query-client";

// TODO: add auth/session + theme providers as the app grows.
export function Providers({ children }: { children: React.ReactNode }) {
  // One QueryClient per mount — avoids sharing cache across requests during SSR.
  const [queryClient] = React.useState(() => makeQueryClient());
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
