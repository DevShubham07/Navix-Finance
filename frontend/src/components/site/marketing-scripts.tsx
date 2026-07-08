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

      // FAQ accordion. `.q` is wrapped in an <h3 class="qh"> for heading semantics, so resolve
      // the container via closest(".qa") rather than parentElement (which is now the <h3>).
      const q = target.closest<HTMLElement>(".qa .q");
      if (q) {
        const qa = q.closest<HTMLElement>(".qa") as HTMLElement;
        const a = qa.querySelector<HTMLElement>(".a");
        const open = qa.classList.toggle("open");
        q.setAttribute("aria-expanded", String(open));
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
        el.style.background = "linear-gradient(90deg,#14A06B " + pct + "%,#F4EBD7 " + pct + "%)";
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

    // Repayment calendar (salary-linked due-date picker) — design "calendar" variant
    const calTitle = document.getElementById("calTitle");
    const calGrid = document.getElementById("calGrid");
    const calAmt = document.getElementById("calAmt") as HTMLInputElement | null;
    if (calTitle && calGrid && calAmt) {
      const calAmtV = document.getElementById("calAmtV");
      const bigEl = document.getElementById("calBig");
      const subEl = document.getElementById("calSub");
      const oA = document.getElementById("calOA");
      const oT = document.getElementById("calOT");
      const oR = document.getElementById("calOR");
      const oI = document.getElementById("calOI");
      const oApr = document.getElementById("calOApr");
      const oTotal = document.getElementById("calOTotal");
      const prevBtn = document.getElementById("calPrev") as HTMLButtonElement | null;
      const nextBtn = document.getElementById("calNext") as HTMLButtonElement | null;
      const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
      const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
      const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
      const DAY = 86_400_000;
      const RATE = 1;
      const MIN_T = 7;
      const MAX_T = 40;
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      const minDate = new Date(today.getTime() + MIN_T * DAY);
      const maxDate = new Date(today.getTime() + MAX_T * DAY);
      let sel = new Date(today.getTime() + 30 * DAY);
      let view = new Date(sel.getFullYear(), sel.getMonth(), 1);
      const minMonth = new Date(minDate.getFullYear(), minDate.getMonth(), 1);
      const maxMonth = new Date(maxDate.getFullYear(), maxDate.getMonth(), 1);
      const sameDay = (a: Date, b: Date) =>
        a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
      const tenure = (d: Date) => Math.round((d.getTime() - today.getTime()) / DAY);
      const inWindow = (d: Date) => {
        const t = tenure(d);
        return t >= MIN_T && t <= MAX_T;
      };
      const fillRange = (el: HTMLInputElement) => {
        const mn = +el.min,
          mx = +el.max,
          v = +el.value;
        const pct = ((v - mn) / (mx - mn)) * 100;
        el.style.background = "linear-gradient(90deg,#14A06B " + pct + "%,#F4EBD7 " + pct + "%)";
      };
      const compute = () => {
        const A = +calAmt.value,
          T = tenure(sel),
          R = RATE;
        const interest = A * (R / 100) * T,
          total = A + interest,
          apr = R * 365;
        if (calAmtV) calAmtV.textContent = rupee(A);
        if (bigEl) bigEl.textContent = sel.getDate() + " " + MON[sel.getMonth()];
        if (subEl) subEl.textContent = WEEKDAYS[sel.getDay()] + " " + sel.getFullYear() + " · " + T + (T === 1 ? " day" : " days") + " away";
        if (oA) oA.textContent = rupee(A);
        if (oT) oT.textContent = T + " days";
        if (oR) oR.textContent = R + "% / day";
        if (oI) oI.textContent = rupee(interest);
        if (oApr) oApr.textContent = apr.toFixed(1) + "%";
        if (oTotal) oTotal.textContent = rupee(total);
        fillRange(calAmt);
      };
      const renderCal = () => {
        calTitle.textContent = MONTHS[view.getMonth()] + " " + view.getFullYear();
        if (prevBtn) prevBtn.disabled = view <= minMonth;
        if (nextBtn) nextBtn.disabled = view >= maxMonth;
        calGrid.innerHTML = "";
        const y = view.getFullYear(),
          m = view.getMonth();
        const startDow = new Date(y, m, 1).getDay();
        const dim = new Date(y, m + 1, 0).getDate();
        for (let i = 0; i < startDow; i++) calGrid.appendChild(document.createElement("div"));
        for (let d = 1; d <= dim; d++) {
          const date = new Date(y, m, d);
          const cell = document.createElement("button");
          cell.type = "button";
          cell.className = "cal-day";
          cell.textContent = String(d);
          if (!inWindow(date)) cell.classList.add("muted");
          if (sameDay(date, today)) cell.classList.add("today");
          if (sameDay(date, sel)) cell.classList.add("sel");
          cell.addEventListener("click", () => {
            if (!inWindow(date)) return;
            sel = date;
            renderCal();
            compute();
          });
          calGrid.appendChild(cell);
        }
      };
      calAmt.oninput = compute;
      document.querySelectorAll<HTMLElement>(".cal-preset").forEach((b) => {
        b.onclick = () => {
          calAmt.value = b.dataset.v || calAmt.value;
          document.querySelectorAll(".cal-preset").forEach((x) => x.classList.remove("on"));
          b.classList.add("on");
          compute();
        };
      });
      if (prevBtn)
        prevBtn.onclick = () => {
          if (view <= minMonth) return;
          view = new Date(view.getFullYear(), view.getMonth() - 1, 1);
          renderCal();
        };
      if (nextBtn)
        nextBtn.onclick = () => {
          if (view >= maxMonth) return;
          view = new Date(view.getFullYear(), view.getMonth() + 1, 1);
          renderCal();
        };
      renderCal();
      compute();
    }

    // Home hero — animated approval offer card (design "calendar" front page)
    const hero = document.querySelector<HTMLElement>(".navix-mkt .hero");
    const offerCard = document.getElementById("offerCard");
    if (hero && offerCard) {
      const reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion:reduce)").matches;
      const amtEl = document.getElementById("offerAmt");
      const rateEl = document.getElementById("offerRate");
      const tenEl = document.getElementById("offerTen");
      const bar = document.getElementById("offerBar");
      if (amtEl && rateEl && tenEl && bar) {
        const fmtAmt = (v: number) => "₹" + Math.round(v).toLocaleString("en-IN");
        const fmtRate = (v: number) => (Math.round(v * 10) / 10).toFixed(1);
        const fmtTen = (v: number) => "" + Math.round(v);
        // NAVIX-correct illustrative offer: max ₹10,00,000 · 1% / day · 30 days.
        const AMT = 1000000, RATE = 1.0, TEN = 30;
        let gen = 0;
        const timers: ReturnType<typeof setTimeout>[] = [];
        const clearTimers = () => { timers.forEach(clearTimeout); timers.length = 0; };
        const countUp = (el: HTMLElement, to: number, dur: number, delay: number, fmt: (n: number) => string) => {
          const my = gen, start = performance.now() + delay;
          const tick = (now: number) => {
            if (my !== gen) return;
            if (now < start) { requestAnimationFrame(tick); return; }
            const p = Math.min((now - start) / dur, 1), e = 1 - Math.pow(1 - p, 3);
            el.textContent = fmt(to * e);
            if (p < 1) requestAnimationFrame(tick); else el.textContent = fmt(to);
          };
          requestAnimationFrame(tick);
          timers.push(setTimeout(() => { if (my === gen) el.textContent = fmt(to); }, delay + dur + 140));
        };
        const setFinal = () => {
          amtEl.textContent = fmtAmt(AMT); rateEl.textContent = fmtRate(RATE); tenEl.textContent = fmtTen(TEN);
          bar.style.transition = "none"; bar.style.width = "100%"; offerCard.classList.add("approved");
        };
        const play = () => {
          if (reduce) { setFinal(); return; }
          gen++; const g = gen; clearTimers();
          offerCard.classList.remove("approved");
          amtEl.textContent = "₹0"; rateEl.textContent = "0.0"; tenEl.textContent = "0";
          bar.style.transition = "none"; bar.style.width = "0%"; void bar.offsetWidth;
          bar.style.transition = "width 1.7s cubic-bezier(.16,1,.3,1) .2s";
          offerCard.classList.remove("shine"); void offerCard.offsetWidth; offerCard.classList.add("shine");
          countUp(amtEl, AMT, 1500, 250, fmtAmt);
          countUp(rateEl, RATE, 1300, 500, fmtRate);
          countUp(tenEl, TEN, 1200, 700, fmtTen);
          requestAnimationFrame(() => { if (g === gen) bar.style.width = "100%"; });
          timers.push(setTimeout(() => { if (g === gen) offerCard.classList.add("approved"); }, 2200));
        };
        let loopTimer: ReturnType<typeof setTimeout> | undefined;
        const schedule = () => { if (loopTimer) clearTimeout(loopTimer); if (reduce) return; loopTimer = setTimeout(() => { play(); schedule(); }, 7200); };
        const enter = () => { hero.classList.remove("go"); void hero.offsetWidth; hero.classList.add("go"); play(); schedule(); };
        const onEnter = () => { play(); schedule(); };
        offerCard.addEventListener("mouseenter", onEnter);
        if (reduce) { hero.classList.add("go"); setFinal(); } else { enter(); }
        cleanups.push(() => { gen++; clearTimers(); if (loopTimer) clearTimeout(loopTimer); offerCard.removeEventListener("mouseenter", onEnter); });
      }
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
