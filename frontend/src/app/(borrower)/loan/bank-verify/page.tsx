"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";

/** Legacy route — bank/penny-drop verification now happens in the onboarding wizard. */
export default function LoanBankVerifyRedirect() {
  const router = useRouter();
  React.useEffect(() => {
    router.replace("/signup/penny-drop");
  }, [router]);
  return (
    <div className="container max-w-content py-16 text-center text-muted">
      <Loader2 size={24} className="mx-auto mb-2 animate-spin" /> Taking you to bank verification…
    </div>
  );
}
