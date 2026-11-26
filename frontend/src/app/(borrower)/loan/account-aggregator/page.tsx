"use client";

import { useRouter } from "next/navigation";
import { AccountAggregatorStep } from "@/components/borrower/account-aggregator-step";
import { useOffer, completeOfferStep } from "@/lib/offer";

export default function LoanAccountAggregatorPage() {
  const router = useRouter();
  const { appId } = useOffer();

  return (
    <AccountAggregatorStep
      mode="offer"
      appId={appId}
      // The step always sits immediately before the sanction letter (the layout owns the resume guard).
      onDone={() => appId != null && void completeOfferStep(appId, "OFFER_ACCOUNT_AGGREGATOR", router, "/loan/sanction-letter")}
    />
  );
}
