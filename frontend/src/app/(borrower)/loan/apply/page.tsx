"use client";

import * as React from "react";
import { useRouter } from "next/navigation";

/**
 * Retired in Phase 1 of the revamp: the borrower no longer picks an amount against a
 * 25%-of-salary formula — the Credit Executive sanctions a figure, and the borrower chooses
 * within it on the Phase-3 offer screen. Kept as a redirect so old links still resolve.
 */
export default function LoanApplyRedirect() {
  const router = useRouter();
  React.useEffect(() => {
    router.replace("/loan/status");
  }, [router]);
  return null;
}
