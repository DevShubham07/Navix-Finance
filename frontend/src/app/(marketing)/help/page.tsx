import type { Metadata } from "next";
import { HelpPage } from "@/components/site/landing/help";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: 'Help & Support | DhanBoost',
  description: 'We\'re here to help: live chat, email and phone support.',
  alternates: { canonical: '/help' },
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <HelpPage />
    </>
  );
}
