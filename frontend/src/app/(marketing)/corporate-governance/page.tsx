import type { Metadata } from "next";
import { html } from "../_content/corporate-governance";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "Corporate Governance Policy — DhanBoost",
  description: "How responsibilities are structured for loans offered through DhanBoost.",
  alternates: { canonical: "/corporate-governance" },
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
