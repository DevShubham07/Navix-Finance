import type { StaffRole } from "@/lib/auth/rbac";

/**
 * Authenticated session shape. Sessions are issued for staff users
 * (back-office) and signed with AUTH_SECRET on the server.
 */
export interface Session {
  userId: string;
  email: string;
  name: string;
  role: StaffRole;
  /** Epoch millis when the session expires. */
  expiresAt: number;
}

/**
 * Resolve the current session from the incoming request context.
 *
 * TODO: read/verify the session cookie (signed with AUTH_SECRET) and return
 * the decoded {@link Session}, or null when unauthenticated.
 */
export async function getSession(): Promise<Session | null> {
  // TODO: implement cookie/JWT verification against AUTH_SECRET.
  return null;
}
