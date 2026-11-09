import type { Metadata } from "next";
import { html } from "../_content/kyc-aml-policy";
import { MarketingHtml } from "@/components/site/marketing-html";
import { PaymentSafetyTicker } from "@/components/site/payment-safety-ticker";

export const metadata: Metadata = {
  title: "KYC & AML Policy — DhanBoost",
  description: "How identity is verified and misuse of the financial system is prevented.",
  alternates: { canonical: "/kyc-aml-policy" },
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
