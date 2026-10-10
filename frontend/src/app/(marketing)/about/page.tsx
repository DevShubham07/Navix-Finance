import type { Metadata } from "next";
import { AboutPage } from "@/components/site/landing/about";

export const metadata: Metadata = {
  title: 'About DhanBoost | Making Credit Calm, Clear & Fair',
  description: 'A better way to borrow for everyday India: transparent, fast and humane.',
  alternates: { canonical: '/about' },
};

export default function Page() {
  return <AboutPage />;
}
