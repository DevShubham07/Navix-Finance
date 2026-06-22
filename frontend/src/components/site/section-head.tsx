import Link from "next/link";
import { cn } from "@/lib/utils";

/** Gold-underlined uppercase eyebrow label. */
export function Eyebrow({ children, className }: { children: React.ReactNode; className?: string }) {
  return <span className={cn("eyebrow", className)}>{children}</span>;
}

/** Standard section heading block (eyebrow + h2 + lead paragraph). */
export function SectionHead({
  eyebrow,
  title,
  children,
  center = false,
  className,
}: {
  eyebrow?: string;
  title: React.ReactNode;
  children?: React.ReactNode;
  center?: boolean;
  className?: string;
}) {
  return (
    <div className={cn("section-head", center && "center", className)}>
      {eyebrow ? <Eyebrow>{eyebrow}</Eyebrow> : null}
      <h2>{title}</h2>
      {children ? <p>{children}</p> : null}
    </div>
  );
}

/** Navy interior-page hero with breadcrumb. */
export function PageHero({
  title,
  children,
  breadcrumb,
}: {
  title: React.ReactNode;
  children?: React.ReactNode;
  breadcrumb?: Array<{ label: string; href?: string }>;
}) {
  return (
    <section className="page-hero">
      <div className="container">
        {breadcrumb?.length ? (
          <div className="breadcrumb">
            {breadcrumb.map((b, i) => (
              <span key={`${b.label}-${i}`}>
                {i > 0 ? <span>/</span> : null}
                {b.href ? <Link href={b.href}>{b.label}</Link> : b.label}
              </span>
            ))}
          </div>
        ) : null}
        <h1>{title}</h1>
        {children ? <p>{children}</p> : null}
      </div>
    </section>
  );
}
