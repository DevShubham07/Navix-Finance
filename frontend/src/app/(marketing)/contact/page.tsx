import type { Metadata } from "next";
import { ContactSection } from "@/components/site/contact-form";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: 'Contact Us — DhanBoost',
  description: 'Let\'s talk — send us a message and we\'ll get back to you.',
  alternates: { canonical: '/contact' },
};

export default function Page() {
  return (
    <>
      <PaymentSafetyTicker />
      <ContactSection />
    </>
  );
}
