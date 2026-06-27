"use client";

import * as React from "react";
import { usePathname, useRouter } from "next/navigation";

/**
 * Ported interactivity for the marketing pages (from the design export's inline JS):
 * scroll-reveal, animated counters, the loan calculator, FAQ accordions, rate-table
 * tabs, and the hero "loan journey". Page-scoped inits re-run on every route change;
 * delegated document listeners attach once. Internal design links (`a[data-link]`)
 * are intercepted for smooth client-side navigation.
 */
export function MarketingScripts() {
  const pathname = usePathname();
  const router = useRouter();

  // --- Delegated listeners (attach once): FAQ, rate-table tabs, SPA links ---
  React.useEffect(() => {
    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement;

      // FAQ accordion
      const q = target.closest<HTMLElement>(".qa .q");
      if (q) {
        const qa = q.parentElement as HTMLElement;
        const a = qa.querySelector<HTMLElement>(".a");
        const open = qa.classList.toggle("open");
        if (a) a.style.maxHeight = open ? a.scrollHeight + "px" : "0";
        return;
      }

      // Rate-table tabs (filter rows by amount)
      const tab = target.closest<HTMLElement>(".rt-tab");
      if (tab) {
        document.querySelectorAll(".rt-tab").forEach((x) => x.classList.remove("on"));
        tab.classList.add("on");
        const amt = tab.getAttribute("data-amt");
        document.querySelectorAll<HTMLElement>("table.rt tbody tr").forEach((tr) => {
          tr.style.display = tr.getAttribute("data-amt") === amt ? "" : "none";
        });
        return;
      }

      // SPA navigation for internal design links
      const link = target.closest<HTMLAnchorElement>("a[data-link]");
      if (link) {
        const href = link.getAttribute("href") || "";
        if (
          href.startsWith("/") &&
          !link.target &&
          !e.metaKey &&
          !e.ctrlKey &&
          !e.shiftKey &&
          !e.altKey &&
          e.button === 0
        ) {
          e.preventDefault();
          router.push(href);
        }
      }
    };

    // Prevent the mock forms in the design markup from doing a real submit.
    const onSubmit = (e: Event) => {
      const form = e.target as HTMLElement;
      if (form.closest(".navix-mkt")) e.preventDefault();
    };

    document.addEventListener("click", onClick);
    document.addEventListener("submit", onSubmit);
    return () => {
      document.removeEventListener("click", onClick);
      document.removeEventListener("submit", onSubmit);
    };
  }, [router]);

  // --- Page-scoped inits: re-run on each route change ---
  React.useEffect(() => {
    const cleanups: Array<() => void> = [];
    const rupee = (n: number) => "₹" + Math.round(n).toLocaleString("en-IN");

    // Counters (data-count → animate the .cv child)
    const animCount = (el: HTMLElement) => {
      const targetV = parseFloat(el.dataset.count || "0");
      const dec = parseInt(el.dataset.dec || "0", 10);
      const pre = el.dataset.pre || "";
      const suf = el.dataset.suf || "";
      const dur = 1600;
      let t0: number | null = null;
      const tick = (t: number) => {
        if (!t0) t0 = t;
        const p = Math.min((t - t0) / dur, 1);
        const ease = 1 - Math.pow(1 - p, 3);
        const v = targetV * ease;
        const cv = el.querySelector<HTMLElement>(".cv");
        if (cv)
          cv.textContent =
            pre + v.toLocaleString("en-IN", { minimumFractionDigits: dec, maximumFractionDigits: dec }) + suf;
        if (p < 1) requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
      el.classList.add("in");
    };

    // Scroll-reveal
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const el = entry.target as HTMLElement;
            el.classList.add("in");
            io.unobserve(el);
            if (el.dataset.count !== undefined) animCount(el);
          }
        });
      },
      { threshold: 0.14 },
    );
    document
      .querySelectorAll<HTMLElement>(".navix-mkt .reveal:not(.in), .navix-mkt [data-count]:not(.in)")
      .forEach((el) => io.observe(el));
    cleanups.push(() => io.disconnect());

    // Hero live counters
    const animateTo = (el: HTMLElement | null, target: number, prefix = "") => {
      if (!el) return;
      let t0: number | null = null;
      const step = (t: number) => {
        if (!t0) t0 = t;
        const p = Math.min((t - t0) / 1500, 1);
        const e = 1 - Math.pow(1 - p, 3);
        el.textContent = prefix + Math.round(target * e).toLocaleString("en-IN");
        if (p < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    };
    animateTo(document.getElementById("liveStat"), 2140);
    animateTo(document.getElementById("snapAmt"), 13000, "₹");

    // Security-note dismiss
    const secx = document.getElementById("secx");
    if (secx) {
      const hide = () => {
        const note = document.getElementById("secnote");
        if (note) note.style.display = "none";
      };
      secx.addEventListener("click", hide);
      cleanups.push(() => secx.removeEventListener("click", hide));
    }

    // Loan calculator
    const amt = document.getElementById("amt") as HTMLInputElement | null;
    const ten = document.getElementById("ten") as HTMLInputElement | null;
    const rate = document.getElementById("rate") as HTMLInputElement | null;
    if (amt && ten && rate) {
      const set = (id: string, v: string) => {
        const el = document.getElementById(id);
        if (el) el.textContent = v;
      };
      const fill = (el: HTMLInputElement) => {
        const min = +el.min,
          max = +el.max,
          v = +el.value;
        const pct = ((v - min) / (max - min)) * 100;
        el.style.background = "linear-gradient(90deg,#E2A02C " + pct + "%,#F4EBD7 " + pct + "%)";
      };
      const highlightRate = (T: number) => {
        document.querySelectorAll<HTMLElement>("table.rt tbody tr").forEach((tr) => {
          const lo = +(tr.dataset.lo || "NaN"),
            hi = +(tr.dataset.hi || "NaN");
          if (!isNaN(lo)) tr.classList.toggle("hot", T >= lo && T <= hi);
        });
      };
      const upd = () => {
        const A = +amt.value,
          T = +ten.value,
          R = +rate.value;
        const interest = A * (R / 100) * T,
          total = A + interest,
          apr = R * 365;
        set("amtV", rupee(A));
        set("tenV", T + " days");
        set("rateV", R + "% / day");
        set("oAmt", rupee(A));
        set("oTen", T + " days");
        set("oRate", R + "%");
        set("oApr", apr.toFixed(1) + "%");
        set("oInt", rupee(interest));
        set("oTotal", rupee(total));
        set("dcAmt", rupee(total));
        const circ = 2 * Math.PI * 52,
          pPrin = A / total,
          dashP = circ * pPrin;
        const pr = document.getElementById("ringPrin") as unknown as SVGCircleElement | null;
        const ir = document.getElementById("ringInt") as unknown as SVGCircleElement | null;
        if (pr && ir) {
          pr.style.strokeDasharray = dashP + " " + circ;
          ir.style.strokeDasharray = circ - dashP + " " + circ;
          ir.style.strokeDashoffset = String(-dashP);
        }
        fill(amt);
        fill(ten);
        fill(rate);
        highlightRate(T);
      };
      amt.oninput = upd;
      ten.oninput = upd;
      rate.oninput = upd;
      document.querySelectorAll<HTMLElement>(".preset").forEach((b) => {
        b.onclick = () => {
          amt.value = b.dataset.v || amt.value;
          document.querySelectorAll(".preset").forEach((x) => x.classList.remove("on"));
          b.classList.add("on");
          upd();
        };
      });
      upd();
    }

    // Hero loan journey (auto-advancing panels)
    const journey = document.getElementById("journey");
    if (journey) {
      const steps = Array.from(journey.querySelectorAll<HTMLElement>(".jrny-step"));
      const panels = Array.from(journey.querySelectorAll<HTMLElement>(".jrny-panel"));
      const prog = document.getElementById("jrnyProgress");
      const fnAmt = document.getElementById("fnAmt");
      const n = panels.length;
      let cur = 0,
        timer: ReturnType<typeof setInterval> | null = null,
        running = false,
        seen = false,
        fnRAF = 0;
      const countFunds = () => {
        if (!fnAmt) return;
        if (fnRAF) cancelAnimationFrame(fnRAF);
        let t0: number | null = null;
        const step = (t: number) => {
          if (!t0) t0 = t;
          const p = Math.min((t - t0) / 1500, 1),
            e = 1 - Math.pow(1 - p, 3);
          fnAmt.textContent = "₹" + Math.round(25000 * e).toLocaleString("en-IN");
          if (p < 1) fnRAF = requestAnimationFrame(step);
        };
        fnRAF = requestAnimationFrame(step);
      };
      const go = (i: number) => {
        cur = ((i % n) + n) % n;
        steps.forEach((s, k) => {
          s.classList.toggle("on", k === cur);
          s.classList.toggle("done", k < cur);
        });
        panels.forEach((p, k) => p.classList.toggle("on", k === cur));
        if (prog) prog.style.width = (cur / (n - 1)) * 75 + "%";
        if (cur === 3) countFunds();
      };
      const next = () => go(cur + 1);
      const start = () => {
        if (running || !seen) return;
        running = true;
        timer = setInterval(next, 4200);
      };
      const stop = () => {
        running = false;
        if (timer) clearInterval(timer);
      };
      steps.forEach((s) =>
        s.addEventListener("click", () => {
          go(+(s.dataset.i || "0"));
          stop();
          start();
        }),
      );
      journey.addEventListener("mouseenter", stop);
      journey.addEventListener("mouseleave", start);
      let jio: IntersectionObserver | null = null;
      if ("IntersectionObserver" in window) {
        jio = new IntersectionObserver(
          (es) => {
            es.forEach((e) => {
              if (e.isIntersecting) {
                seen = true;
                go(cur);
                start();
              } else stop();
            });
          },
          { threshold: 0.35 },
        );
        jio.observe(journey);
      } else {
        seen = true;
        go(0);
        start();
      }
      cleanups.push(() => {
        stop();
        if (fnRAF) cancelAnimationFrame(fnRAF);
        if (jio) jio.disconnect();
      });
    }

    return () => cleanups.forEach((fn) => fn());
  }, [pathname]);

  return null;
}
