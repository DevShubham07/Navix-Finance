import Link from "next/link";
import Image from "next/image";
import { cn } from "@/lib/utils";

/**
 * NAVIX wordmark + emblem mark. The mark renders the brand icon
 * (`/navix-mark.png`) inside the navy tile; used in the header, footer and
 * staff sidebar.
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
      <span className="brand-mark brand-mark--img">
        <Image src="/navix-mark.png" alt="NAVIX" width={42} height={42} priority />
      </span>
      <span className="brand-text">
        <span className="brand-name">NAVIX</span>
        <span className="brand-tag">{tag}</span>
      </span>
    </Link>
  );
}
