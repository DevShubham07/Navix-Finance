import type { Metadata } from "next";
import { FaqSchema } from "@/components/site/faq-schema";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";
import { FaqPage } from "@/components/site/landing/faq";

export const metadata: Metadata = {
  title: 'Frequently Asked Questions | DhanBoost',
  description: 'Answers on applications, rates, repayments and grievance redressal.',
  alternates: { canonical: '/faq' },
};

export default function Page() {
  return (
    <>
      <FaqSchema />
      <PaymentSafetyTicker />
      <FaqPage />
    </>
  );
}
