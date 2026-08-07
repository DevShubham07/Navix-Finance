import type { Metadata } from "next";
import { html } from "../_content/privacy";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: 'Privacy Policy — DhanBoost',
  description: 'How DhanBoost collects, uses, shares and protects your information.',
  alternates: { canonical: '/privacy' },
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <MarketingHtml html={html} />
    </>
  );
}
