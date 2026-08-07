import type { Metadata } from "next";
import { html } from "../_content/support";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: 'Help & Support — DhanBoost',
  description: 'We\'re here to help — live chat, email and phone support.',
  alternates: { canonical: '/help' },
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <MarketingHtml html={html} />
    </>
  );
}
