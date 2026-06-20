"use client";

import type { Session } from "@/lib/auth/session";

/**
 * Client hook exposing the current staff session.
 *
 * TODO: wire to a session provider / react-query query that hits the BFF
 * (/api/auth) and returns the decoded session.
 */
export function useSession(): {
  session: Session | null;
  isLoading: boolean;
} {
  // TODO: replace with real session fetching.
  return { session: null, isLoading: false };
}
