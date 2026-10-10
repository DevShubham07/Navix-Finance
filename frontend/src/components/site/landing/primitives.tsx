import * as React from "react";
import Link from "next/link";
import { ArrowRight, Check } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Landing primitives — the layout language of the public marketing pages (home, about,
 * how-it-works, header, footer): centred section heads with pill labels, dotted inset panels,
 * soft "3D" white tiles that float at a tilt, blueprint hairline grids, bento cards, plan cards.
 *
 * Every tree that uses these sits inside an `.lp` element (see marketing-theme.css, "landing
 * layout language"), which opts it out of the legacy marketing resets so Tailwind utilities apply.
 * Server components throughout — no client state.
 */

/* ------------------------------------------------------------------ layout */

export function Container({ className, children }: { className?: string; children: React.ReactNode }) {
  return <div className={cn("mx-auto w-full max-w-[1240px] px-4 sm:px-6", className)}>{children}</div>;
}

/** A page section. `overflow-hidden` lets blueprint rules and bleeding rows run past the edges. */
export function Section({
  id,
  labelledBy,
  className,
  children,
}: {
  id?: string;
  labelledBy?: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} aria-labelledby={labelledBy} className={cn("relative overflow-hidden py-[clamp(72px,9vw,120px)]", className)}>
      {children}
    </section>
  );
}

/* ------------------------------------------------------------------ section heads */

/** The small white pill above every section headline ("Solutions", "Pricing"). */
export function SectionLabel({ children, icon, className }: { children: React.ReactNode; icon?: React.ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full border border-line bg-paper px-3.5 py-1.5 text-[12.5px] font-medium leading-none tracking-[-0.005em] text-ink shadow-pill",
        className,
      )}
    >
      {icon ?? <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-gold" />}
      {children}
    </span>
  );
}

/** Two-line headline: line one in ink, line two muted. */
export function Headline({
  as: Tag = "h2",
  id,
  lead,
  muted,
  size = "h2",
  className,
}: {
  as?: "h1" | "h2" | "h3";
  id?: string;
  lead: React.ReactNode;
  muted?: React.ReactNode;
  size?: "h1" | "h2";
  className?: string;
}) {
  return (
    <Tag id={id} className={cn(size === "h1" ? "lp-h1" : "lp-h2", className)}>
      <span className="block">{lead}</span>
      {muted && <span className="block text-muted">{muted}</span>}
    </Tag>
  );
}

export function SectionHead({
  label,
  labelIcon,
  id,
  lead,
  muted,
  sub,
  align = "center",
  className,
}: {
  label?: React.ReactNode;
  labelIcon?: React.ReactNode;
  id?: string;
  lead: React.ReactNode;
  muted?: React.ReactNode;
  sub?: React.ReactNode;
  align?: "center" | "left";
  className?: string;
}) {
  const center = align === "center";
  return (
    <div className={cn("relative", center ? "mx-auto max-w-[780px] text-center" : "max-w-[620px]", className)}>
      {label && <SectionLabel icon={labelIcon}>{label}</SectionLabel>}
      <Headline id={id} lead={lead} muted={muted} className={label ? "mt-5" : undefined} />
      {sub && (
        <p className={cn("m-0 mt-5 max-w-[560px] text-[15.5px] leading-[1.6] text-slate", center && "mx-auto")}>{sub}</p>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ buttons */

type ButtonTone = "accent" | "ink" | "outline" | "white";

const BUTTON_TONES: Record<ButtonTone, string> = {
  // Ink text on the ember accent: ≥ 6:1 (white on ember would fail 4.5:1).
  accent: "bg-gold text-ink hover:text-ink hover:bg-gold-400 shadow-gold",
  ink: "bg-navy text-white hover:text-white hover:bg-navy-700 shadow-sm",
  outline: "border border-[rgb(var(--c-line-2))] bg-paper text-ink hover:text-ink hover:border-navix-300 shadow-pill",
  white: "bg-paper text-ink hover:text-ink hover:bg-grey-50 shadow-sm",
};

export function ButtonLink({
  href,
  tone = "accent",
  size = "md",
  arrow = false,
  className,
  children,
}: {
  href: string;
  tone?: ButtonTone;
  size?: "sm" | "md" | "lg";
  arrow?: boolean;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      className={cn(
        "group inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-full font-semibold tracking-[-0.01em] transition-[transform,background-color,box-shadow,border-color] duration-300 ease-out-expo hover:-translate-y-px focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-gold-dark",
        size === "sm" && "h-9 px-4 text-[13px]",
        size === "md" && "h-11 px-5 text-[14px]",
        size === "lg" && "h-[52px] px-7 text-[15px]",
        BUTTON_TONES[tone],
        className,
      )}
    >
      {children}
      {arrow && <ArrowRight aria-hidden size={16} strokeWidth={2.2} className="transition-transform duration-300 group-hover:translate-x-0.5" />}
    </Link>
  );
}

/* ------------------------------------------------------------------ panels + tiles */

/** The big rounded inset panel with the dotted micro-grid (hero, footer, CTA bands). */
export function InsetPanel({ className, glow = true, children }: { className?: string; glow?: boolean; children: React.ReactNode }) {
  return (
    <div className={cn("lp-dots relative isolate overflow-hidden rounded-[24px] border border-line sm:rounded-[32px]", className)}>
      {glow && <div aria-hidden className="lp-dots-glow pointer-events-none absolute inset-0 -z-10" />}
      {children}
    </div>
  );
}

/**
 * A floating, tilted fragment. Positioned absolutely by `className` (e.g. "left-[3%] top-[12%]");
 * `rotate` is the tilt in degrees, `delay` staggers the gentle bob and the entrance.
 * Always decorative — hidden from assistive tech.
 */
export function FloatTile({
  className,
  rotate = 0,
  delay = 0,
  style,
  children,
}: {
  className?: string;
  rotate?: number;
  delay?: number;
  /** Extra inline style — e.g. computed left/top for scattered fields. */
  style?: React.CSSProperties;
  children: React.ReactNode;
}) {
  return (
    <div
      aria-hidden
      className={cn("lp-enter pointer-events-none absolute select-none", className)}
      style={{ ...style, "--lp-e": `${0.15 + delay * 0.12}s` } as React.CSSProperties}
    >
      <div className="lp-float" style={{ "--lp-r": `${rotate}deg` } as React.CSSProperties}>
        <div className="lp-float-in" style={{ "--lp-d": `${-delay * 1.3}s` } as React.CSSProperties}>
          {children}
        </div>
      </div>
    </div>
  );
}

/** White rounded square tile with an icon (app-icon look). */
export function IconTile({
  children,
  size = 64,
  className,
}: {
  children: React.ReactNode;
  size?: number;
  className?: string;
}) {
  return (
    <span
      className={cn("lp-tile inline-grid shrink-0 place-items-center text-ink", className)}
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.3) }}
    >
      {children}
    </span>
  );
}

/** Calendar tile ("30" on a white tile with an accent month band). */
export function CalendarTile({ day, month, size = 64, className }: { day: string; month: string; size?: number; className?: string }) {
  return (
    <span
      className={cn("lp-tile inline-flex shrink-0 flex-col overflow-hidden text-center", className)}
      style={{ width: size, height: size, borderRadius: Math.round(size * 0.28) }}
    >
      <span className="bg-gold py-[3px] text-[10px] font-semibold uppercase leading-none tracking-[0.08em] text-ink" style={{ fontSize: Math.max(9, size * 0.15) }}>
        {month}
      </span>
      <span className="lp-fig grid flex-1 place-items-center text-ink" style={{ fontSize: size * 0.42 }}>
        {day}
      </span>
    </span>
  );
}

/* ------------------------------------------------------------------ blueprint grid */

/**
 * Columns separated by vertical hairlines, between two horizontal hairlines that run to the page
 * edge, with small ring nodes where the lines meet. Collapses to a stack on small screens.
 */
export function BlueprintGrid({
  cols = 3,
  className,
  children,
}: {
  cols?: 2 | 3 | 4;
  className?: string;
  children: React.ReactNode;
}) {
  const items = React.Children.toArray(children);
  return (
    <div className={cn("relative", className)}>
      <span aria-hidden className="lp-rule top-0" />
      <span aria-hidden className="lp-rule bottom-0" />
      <div
        className={cn(
          "relative grid",
          cols === 2 && "md:grid-cols-2",
          cols === 3 && "md:grid-cols-3",
          cols === 4 && "grid-cols-2 lg:grid-cols-4",
        )}
      >
        {items.map((child, i) => (
          <div
            key={i}
            className={cn(
              "relative min-w-0",
              i > 0 && cols !== 4 && "border-t border-line md:border-t-0",
              cols === 4 && i >= 2 && "border-t border-line lg:border-t-0",
            )}
          >
            {i > 0 && (
              <span aria-hidden className={cn("absolute inset-y-0 left-0 w-px bg-line", cols === 4 ? (i === 2 ? "hidden lg:block" : "block") : "hidden md:block")}>
                <span className="lp-node -left-[4px] -top-[5px]" />
                <span className="lp-node -bottom-[5px] -left-[4px]" />
              </span>
            )}
            {child}
          </div>
        ))}
      </div>
    </div>
  );
}

/** One cell of a blueprint grid: icon tile + title + text. */
export function BlueprintItem({
  icon,
  title,
  children,
  className,
}: {
  icon: React.ReactNode;
  title: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("px-2 py-9 sm:px-8 sm:py-11", className)}>
      <IconTile size={48} className="text-gold-dark">
        {icon}
      </IconTile>
      <h3 className="lp-h3 mt-6">{title}</h3>
      <p className="m-0 mt-2 max-w-[34ch] text-[14.5px] leading-[1.6] text-slate">{children}</p>
    </div>
  );
}

/* ------------------------------------------------------------------ cards */

/** Bento card: a product-UI illustration on top, title + one line underneath. */
export function BentoCard({
  title,
  children,
  art,
  dashed = false,
  className,
}: {
  title: React.ReactNode;
  children: React.ReactNode;
  art: React.ReactNode;
  dashed?: boolean;
  className?: string;
}) {
  return (
    <article
      className={cn(
        "flex min-w-0 flex-col rounded-[26px] p-2.5",
        dashed ? "border-[1.5px] border-dashed border-navix-200 bg-paper/60" : "lp-card",
        className,
      )}
    >
      <div aria-hidden className="relative h-[216px] overflow-hidden rounded-[20px] border border-line bg-grey-50">
        {art}
      </div>
      <div className="px-3.5 pb-4 pt-5">
        <h3 className="lp-h3">{title}</h3>
        <p className="m-0 mt-1.5 text-[14px] leading-[1.55] text-slate">{children}</p>
      </div>
    </article>
  );
}

/** Check-mark list used by plan cards and eligibility lists. */
export function CheckList({ items, tone = "light", className }: { items: React.ReactNode[]; tone?: "light" | "dark"; className?: string }) {
  return (
    <ul className={cn("m-0 grid list-none gap-3 p-0", className)}>
      {items.map((it, i) => (
        <li key={i} className={cn("flex items-start gap-2.5 text-[14px] leading-[1.5]", tone === "dark" ? "text-white" : "text-slate")}>
          <span
            aria-hidden
            className={cn(
              "mt-px grid h-[20px] w-[20px] shrink-0 place-items-center rounded-full",
              tone === "dark" ? "bg-white/20 text-white" : "bg-gold-50 text-gold-dark",
            )}
          >
            <Check size={12} strokeWidth={3} />
          </span>
          <span className="min-w-0">{it}</span>
        </li>
      ))}
    </ul>
  );
}

export function PlanCard({
  kicker,
  figure,
  unit,
  caption,
  items,
  cta,
  raised = false,
  extra,
  className,
  children,
}: {
  kicker: string;
  figure: string;
  unit?: string;
  caption: React.ReactNode;
  items: React.ReactNode[];
  cta: { href: string; label: string };
  raised?: boolean;
  extra?: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
}) {
  return (
    <article
      className={cn(
        "relative flex min-w-0 flex-col rounded-[28px] p-7 sm:p-8",
        // gold-dark keeps white text ≥ 4.5:1 while still reading as the ember accent.
        raised ? "bg-gold-dark text-white shadow-[0_30px_60px_-28px_rgb(var(--c-gold-600)/.75)] lg:-my-6 lg:py-12" : "lp-card",
        className,
      )}
    >
      {raised && (
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 rounded-[28px]"
          style={{ background: "radial-gradient(120% 70% at 100% 0%, rgb(var(--c-gold-500) / .9), transparent 60%)" }}
        />
      )}
      {extra}
      <div className="relative flex flex-1 flex-col">
        <p className={cn("m-0 text-[13px] font-semibold uppercase tracking-[0.08em]", raised ? "text-white" : "text-gold-dark")}>{kicker}</p>
        <p className="m-0 mt-5 flex items-baseline gap-2">
          <span className={cn("lp-fig text-[clamp(2.6rem,4.4vw,3.4rem)]", raised ? "text-white" : "text-ink")}>{figure}</span>
          {unit && <span className={cn("text-[15px] font-medium", raised ? "text-white" : "text-slate")}>{unit}</span>}
        </p>
        <p className={cn("m-0 mt-2 text-[14.5px] leading-[1.55]", raised ? "text-white" : "text-slate")}>{caption}</p>
        <div className={cn("my-6 h-px", raised ? "bg-white/25" : "bg-line")} />
        {children}
        <CheckList items={items} tone={raised ? "dark" : "light"} className="mb-8" />
        <ButtonLink href={cta.href} tone={raised ? "white" : "accent"} arrow className="mt-auto w-full">
          {cta.label}
        </ButtonLink>
      </div>
    </article>
  );
}

/** Quote card for the reviews masonry. */
export function QuoteCard({
  quote,
  name,
  role,
  avatar,
  className,
  children,
}: {
  quote: React.ReactNode;
  name: React.ReactNode;
  role: React.ReactNode;
  avatar: React.ReactNode;
  className?: string;
  children?: React.ReactNode;
}) {
  return (
    <figure className={cn("lp-card relative m-0 break-inside-avoid rounded-[24px] p-6 sm:p-7", className)}>
      <blockquote className="m-0 text-[15px] leading-[1.6] text-ink">{quote}</blockquote>
      {children}
      <figcaption className="mt-6 flex items-center gap-3">
        {avatar}
        <span className="min-w-0 text-[13.5px] leading-tight">
          <span className="block text-slate">{name}</span>
          <span className="block font-medium text-ink">{role}</span>
        </span>
      </figcaption>
    </figure>
  );
}

/** "Text link →" used under grids. */
export function ArrowLink({ href, children, className }: { href: string; children: React.ReactNode; className?: string }) {
  return (
    <Link
      href={href}
      className={cn(
        "group inline-flex items-center gap-1.5 rounded-full text-[14.5px] font-medium text-ink underline-offset-4 hover:text-ink hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-gold-dark",
        className,
      )}
    >
      {children}
      <ArrowRight aria-hidden size={15} className="transition-transform duration-300 group-hover:translate-x-0.5" />
    </Link>
  );
}
