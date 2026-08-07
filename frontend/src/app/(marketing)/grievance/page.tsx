import type { Metadata } from "next";
import { html } from "../_content/grievance";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: 'Grievance Redressal — DhanBoost',
  description: 'How to raise a complaint and reach our Grievance Officer.',
  alternates: { canonical: '/grievance' },
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <MarketingHtml html={html} />
    </>
  );
}
