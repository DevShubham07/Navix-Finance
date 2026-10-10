"use client";

import * as React from "react";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ArrowRight,
  Calculator,
  ChevronDown,
  CircleAlert,
  CircleHelp,
  IndianRupee,
  LifeBuoy,
  Menu,
  MessageSquare,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Public marketing header — the landing layout language: logo left, plain text links centred,
 * "Sign in" + an outlined pill CTA right; a light white bar that gains a hairline once scrolled.
 * Dropdowns open on hover and keyboard focus; below `lg` a drawer takes over (legacy `.drawer`
 * styles in marketing-theme.css, closed on route change, Escape, or the scrim).
 */

type DropItem = { href: string; title: string; sub?: string; icon: React.ReactNode };
type NavItem = { href: string; label: string; drop?: DropItem[] };

const NAV: NavItem[] = [
  { href: "/how-it-works", label: "How It Works" },
  {
    href: "/products",
    label: "Loan Products",
    drop: [
      { href: "/products", title: "Instant Personal Loan", sub: "Up to 25% of your salary", icon: <IndianRupee size={16} /> },
      { href: "/calculator", title: "Calculator & Rates", sub: "Plan repayment", icon: <Calculator size={16} /> },
    ],
  },
  { href: "/calculator", label: "Calculator" },
  { href: "/about", label: "About" },
  {
    href: "/help",
    label: "Support",
    drop: [
      { href: "/help", title: "Help Center", icon: <LifeBuoy size={16} /> },
      { href: "/faq", title: "FAQs", icon: <CircleHelp size={16} /> },
      { href: "/contact", title: "Contact Us", icon: <MessageSquare size={16} /> },
      { href: "/grievance", title: "Grievance Redressal", icon: <CircleAlert size={16} /> },
    ],
  },
];

const DRAWER_LINKS = [
  { href: "/", label: "Home" },
  { href: "/how-it-works", label: "How It Works" },
  { href: "/products", label: "Loan Products" },
  { href: "/calculator", label: "Calculator & Rates" },
  { href: "/about", label: "About Us" },
  { href: "/blog", label: "Resources" },
  { href: "/help", label: "Support" },
  { href: "/contact", label: "Contact" },
];

const FOCUS = "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold-dark";

export function MarketingHeader() {
  const pathname = usePathname();
  const [scrolled, setScrolled] = React.useState(false);
  const [drawerOpen, setDrawerOpen] = React.useState(false);

  React.useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  // Close the drawer whenever the route changes.
  React.useEffect(() => {
    setDrawerOpen(false);
  }, [pathname]);

  React.useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawerOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  const isActive = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));
  const close = () => setDrawerOpen(false);

  return (
    <>
      <header
        id="header"
        className={cn(
          "lp sticky top-0 z-[90] border-b transition-[background-color,border-color,box-shadow] duration-300",
          scrolled ? "border-line bg-paper/90 shadow-[0_8px_24px_-20px_rgb(var(--c-shadow)/.35)] backdrop-blur-md" : "border-transparent bg-ivory/80 backdrop-blur-md",
        )}
      >
        <nav aria-label="Main" className="mx-auto grid h-[68px] w-full max-w-[1400px] grid-cols-[1fr_auto] items-center gap-4 px-4 sm:px-6 lg:h-[76px] lg:grid-cols-[1fr_auto_1fr]">
          <Link href="/" aria-label="DhanBoost home" className={cn("flex w-fit items-center gap-2.5 rounded-xl text-ink hover:text-ink", FOCUS)}>
            <Image src="/navix-mark.png" alt="" width={36} height={36} priority className="h-9 w-9 rounded-[10px]" />
            <span className="text-[18px] font-semibold tracking-[-0.03em]">DhanBoost</span>
          </Link>

          <ul className="m-0 hidden list-none items-center gap-1 p-0 lg:flex">
            {NAV.map((item) =>
              item.drop ? (
                <li key={item.label} className="lp-has-drop relative">
                  <Link
                    href={item.href}
                    aria-current={isActive(item.href) ? "page" : undefined}
                    className={cn(
                      "inline-flex items-center gap-1 rounded-full px-3.5 py-2 text-[14px] font-medium tracking-[-0.01em] transition-colors hover:text-ink",
                      isActive(item.href) ? "text-ink" : "text-slate",
                      FOCUS,
                    )}
                  >
                    {item.label}
                    <ChevronDown aria-hidden size={14} className="opacity-60" />
                  </Link>
                  <div className="lp-drop absolute left-1/2 top-full z-10 pt-2">
                    <ul className="m-0 grid min-w-[250px] list-none gap-0.5 rounded-[18px] border border-line bg-paper p-2 shadow-lg">
                      {item.drop.map((d) => (
                        <li key={d.title}>
                          <Link
                            href={d.href}
                            className={cn("flex items-center gap-3 rounded-[12px] px-3 py-2.5 text-ink hover:bg-grey-100 hover:text-ink", FOCUS)}
                          >
                            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-[10px] bg-gold-50 text-gold-dark">{d.icon}</span>
                            <span className="leading-tight">
                              <span className="block text-[13.5px] font-medium">{d.title}</span>
                              {d.sub && <span className="block text-[12px] text-slate">{d.sub}</span>}
                            </span>
                          </Link>
                        </li>
                      ))}
                    </ul>
                  </div>
                </li>
              ) : (
                <li key={item.label}>
                  <Link
                    href={item.href}
                    aria-current={isActive(item.href) ? "page" : undefined}
                    className={cn(
                      "relative inline-flex rounded-full px-3.5 py-2 text-[14px] font-medium tracking-[-0.01em] transition-colors hover:text-ink",
                      isActive(item.href) ? "text-ink" : "text-slate",
                      FOCUS,
                    )}
                  >
                    {item.label}
                    {isActive(item.href) && <span aria-hidden className="absolute bottom-0.5 left-1/2 h-1 w-1 -translate-x-1/2 rounded-full bg-gold" />}
                  </Link>
                </li>
              ),
            )}
          </ul>

          <div className="flex items-center justify-end gap-2 sm:gap-4">
            <Link href="/login" className={cn("hidden rounded-full px-2 py-1 text-[14px] font-medium text-ink hover:text-gold-dark sm:inline-flex", FOCUS)}>
              Sign in
            </Link>
            <Link
              href="/signup/start"
              className={cn(
                "group hidden h-10 items-center gap-1.5 rounded-full border border-[rgb(var(--c-line-2))] bg-paper px-4 text-[14px] font-semibold tracking-[-0.01em] text-ink shadow-pill transition-[transform,border-color,box-shadow] duration-300 hover:-translate-y-px hover:border-navix-300 hover:text-ink sm:inline-flex",
                FOCUS,
              )}
            >
              Apply now
              <ArrowRight aria-hidden size={15} className="transition-transform duration-300 group-hover:translate-x-0.5" />
            </Link>
            <button
              type="button"
              aria-label="Menu"
              aria-expanded={drawerOpen}
              aria-controls="drawer"
              onClick={() => setDrawerOpen((v) => !v)}
              className={cn("grid h-10 w-10 place-items-center rounded-full border border-line bg-paper text-ink shadow-pill lg:hidden", FOCUS)}
            >
              {drawerOpen ? <X size={18} /> : <Menu size={18} />}
            </button>
          </div>
        </nav>
      </header>

      <aside className={`drawer${drawerOpen ? " open" : ""}`} id="drawer" aria-hidden={!drawerOpen} inert={!drawerOpen}>
        <div className="drawer-top">
          <span className="brand-txt">
            <b style={{ fontSize: "1.04rem", color: "var(--navy-800)" }}>DhanBoost</b>
          </span>
          <button className="drawer-close" aria-label="Close menu" onClick={close}>
            ✕
          </button>
        </div>
        <nav aria-label="Mobile">
          {DRAWER_LINKS.map((l) => (
            <Link key={l.href} href={l.href} onClick={close} aria-current={isActive(l.href) ? "page" : undefined}>
              {l.label}
            </Link>
          ))}
        </nav>
        <div className="drawer-cta">
          <Link href="/signup/start" className="btn btn-gold btn-block" onClick={close}>
            Apply Now
          </Link>
          <Link href="/login" className="btn btn-ghost btn-block" onClick={close}>
            Sign In
          </Link>
        </div>
      </aside>
      <div className={`scrim${drawerOpen ? " open" : ""}`} id="scrim" onClick={close} />
    </>
  );
}
