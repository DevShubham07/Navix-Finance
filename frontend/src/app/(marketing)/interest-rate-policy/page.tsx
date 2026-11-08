import type { Metadata } from "next";
import { html } from "../_content/interest-rate-policy";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "Interest Rate Policy — DhanBoost",
  description: "How interest and charges are set on loans offered through DhanBoost.",
  alternates: { canonical: "/interest-rate-policy" },
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
