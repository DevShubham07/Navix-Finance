import type { Metadata } from "next";
import { html } from "../_content/loan-agreement-access-policy";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "Loan Agreement Access Policy — DhanBoost",
  description: "How you access, review, accept and keep your loan documents.",
  alternates: { canonical: "/loan-agreement-access-policy" },
  robots: { index: false }, // until approved under DB-LGL-004
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <MarketingHtml html={html} />
    </>
  );
}
