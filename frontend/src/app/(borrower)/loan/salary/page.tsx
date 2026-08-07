"use client";

import * as React from "react";
import { useRouter } from "next/navigation";

/** Retired in Phase 1 — the salary date is captured at intake and the repayment date is set by credit. */
export default function LoanSalaryRedirect() {
  const router = useRouter();
  React.useEffect(() => {
    router.replace("/loan/status");
  }, [router]);
  return null;
}
