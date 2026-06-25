"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/**
 * Retired. The standalone "live backend" page has been folded into the designed
 * borrower journey — the polished flow (signup → kyc → loan → dashboard) now
 * talks to the real backend directly. This stub keeps old links/bookmarks from
 * 404ing by redirecting to the dashboard, which resolves the live application.
 */
export default function ApplyLiveRedirect() {
  const router = useRouter();
  React.useEffect(() => {
    router.replace("/dashboard");
  }, [router]);

  return (
    <div className="container max-w-content py-16 text-center text-muted">
      <Loader2 size={20} className="mx-auto mb-2 animate-spin" />
      Redirecting to your dashboard…
    </div>
  );
}
