import { QueryClient } from "@tanstack/react-query";

/**
 * Factory for a configured @tanstack/react-query QueryClient.
 *
 * TODO: tune defaults (staleTime, retry policy) once data-fetching patterns
 * are finalized.
 */
export function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 60 * 1000,
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  });
}
