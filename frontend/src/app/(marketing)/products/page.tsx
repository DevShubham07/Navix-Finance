import type { Metadata } from "next";
import { ProductsPage } from "@/components/site/landing/products";

export const metadata: Metadata = {
  title: 'Loan Products | DhanBoost',
  description: 'Loan products built for real life: personal, salary advance, business and education.',
  alternates: { canonical: '/products' },
};

export default function Page() {
  return <ProductsPage />;
}
