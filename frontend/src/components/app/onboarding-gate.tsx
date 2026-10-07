"use client";

import * as React from "react";
import { usePathname, useRouter } from "next/navigation";
import { useOnboardingGate } from "@/lib/api/live-journey";

/** Routes a frozen (onboarding-paused) borrower may still reach. */
const ALLOWED = ["/apply", "/login", "/settings", "/support"];

/**
 * Onboarding-paused freeze. Mounted once in the borrower layout: while the gate says FORM or SUBMITTED
 * the borrower is sent to `/apply` from anywhere else. Renders nothing; while loading or OPEN it
 * does nothing, so the normal app never flashes a redirect.
 */
export function OnboardingGate() {
  const router = useRouter();
  const pathname = usePathname();
  const gate = useOnboardingGate().data?.gate;

  React.useEffect(() => {
    if (gate !== "FORM" && gate !== "SUBMITTED") return;
    if (ALLOWED.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return;
    router.replace("/apply");
  }, [gate, pathname, router]);

  return null;
}
