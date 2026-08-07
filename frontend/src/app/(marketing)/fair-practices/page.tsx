import type { Metadata } from "next";
import { html } from "../_content/fair-practices";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: 'Fair Practices Code — DhanBoost',
  description: 'Our commitment to transparent, non-coercive, responsible lending.',
  alternates: { canonical: '/fair-practices' },
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <MarketingHtml html={html} />
    </>
  );
}
