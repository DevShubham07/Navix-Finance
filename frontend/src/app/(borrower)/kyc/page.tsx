"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/**
 * Legacy KYC landing. KYC is now captured inside the onboarding wizard and
 * tracked live on the status page — send the borrower there.
 */
export default function KycRedirect() {
  const router = useRouter();
  React.useEffect(() => {
    router.replace("/loan/status");
  }, [router]);
  return (
    <div className="container max-w-content py-16 text-center text-muted">
      <Loader2 size={24} className="mx-auto mb-2 animate-spin" /> Loading your application status…
    </div>
  );
}
