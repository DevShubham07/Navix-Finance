import type { Metadata } from "next";
import { html } from "../_content/kfs-information-policy";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "KFS Information Policy — DhanBoost",
  description: "What the Key Facts Statement for your loan contains, and when and how you receive it.",
  alternates: { canonical: "/kfs-information-policy" },
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
