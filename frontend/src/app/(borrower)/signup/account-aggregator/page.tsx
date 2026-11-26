"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AccountAggregatorStep } from "@/components/borrower/account-aggregator-step";
import { useOnboarding, completeStep } from "@/lib/onboarding";

export default function SignupAccountAggregatorPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  React.useEffect(() => {
    if (mounted && appId == null) router.replace("/signup/start");
  }, [mounted, appId, router]);

  return (
    <>
      <AccountAggregatorStep
        mode="intake"
        appId={appId}
        onDone={() => appId != null && void completeStep(appId, "ACCOUNT_AGGREGATOR", router, "/signup/payslips")}
      />
      <Link href="/signup/bank" className="btn btn-outline btn-sm mt-4">
        Back
      </Link>
    </>
  );
}
