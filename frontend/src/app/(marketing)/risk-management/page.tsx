import type { Metadata } from "next";
import { html } from "../_content/risk-management";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "Risk Management Policy — DhanBoost",
  description: "How risks are identified and managed on loans offered through DhanBoost.",
  alternates: { canonical: "/risk-management" },
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
