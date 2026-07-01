import "./marketing-theme.css";
import { MarketingHeader } from "@/components/site/marketing-header";
import { MarketingFooter } from "@/components/site/marketing-footer";
import { MarketingScripts } from "@/components/site/marketing-scripts";
import { StructuredData } from "@/components/site/structured-data";

/**
 * Public marketing area — the design-export chrome (header + drawer + footer) and
 * interactivity. Everything is wrapped in `.navix-mkt` so marketing-theme.css
 * (which is fully scoped under that class) styles it, with zero bleed into the
 * borrower/staff functional app.
 */
export default function MarketingLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="navix-mkt">
      <StructuredData />
      <MarketingHeader />
      <main>{children}</main>
      <MarketingFooter />
      <MarketingScripts />
    </div>
  );
}
