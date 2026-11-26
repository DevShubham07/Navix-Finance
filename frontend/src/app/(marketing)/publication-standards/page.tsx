import type { Metadata } from "next";
import { html } from "../_content/publication-standards";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "Publication Standards — DhanBoost",
  description: "The controls applied to DhanBoost's public website and customer-facing documents.",
  alternates: { canonical: "/publication-standards" },
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
