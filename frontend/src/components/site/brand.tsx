import Link from "next/link";
import { cn } from "@/lib/utils";

/**
 * NAVIX wordmark + serif "N" mark with the gold underbar, exactly as in the
 * design system. Used in the header and footer.
 */
export function Brand({
  href = "/",
  className,
  tag = "Lending Platform",
}: {
  href?: string;
  className?: string;
  tag?: string;
}) {
  return (
    <Link href={href} className={cn("brand", className)} aria-label="NAVIX home" style={{ textDecoration: "none" }}>
      <span className="brand-mark">
        <span className="bm-n">N</span>
      </span>
      <span className="brand-text">
        <span className="brand-name">NAVIX</span>
        <span className="brand-tag">{tag}</span>
      </span>
    </Link>
  );
}
