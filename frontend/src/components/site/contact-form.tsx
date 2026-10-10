"use client";

import * as React from "react";
import { ArrowRight, Check, Mail, MapPin, Phone, ShieldAlert } from "lucide-react";
import { config } from "@/lib/config";
import { BRAND } from "@/lib/brand";
import {
  COMMUNICATION_CONSENT_TEXT,
  COMMUNICATION_PRIVACY_TEXT,
} from "@/lib/communication-consent";
import { cn } from "@/lib/utils";
import { PageHero } from "./landing/page-kit";
import { Container, IconTile } from "./landing/primitives";

const TOPICS = ["General enquiry", "Application help", "Repayment", "Grievance", "Report fraud"];

type Status = "idle" | "sending" | "sent" | "error";

const FOCUS = "focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold-dark";
const FIELD =
  "block w-full rounded-[14px] border border-[rgb(var(--c-line-2))] bg-paper px-4 py-3 text-[15px] text-ink shadow-xs outline-none transition-[border-color,box-shadow] placeholder:text-muted focus:border-gold focus:shadow-focus disabled:opacity-60";
const LABEL = "mb-1.5 block text-[13px] font-medium text-ink";

/**
 * The live "Contact us" page for the marketing site, in the landing layout language. The form posts
 * to the BFF `/api/contact` endpoint, which emails the enquiry to the DhanBoost support inbox; on
 * success it swaps the form for a confirmation. Communication consent is required before sending.
 */
export function ContactSection() {
  return (
    <div className="lp">
      <PageHero
        trail={[{ label: "Contact" }]}
        label="Contact"
        lead="Let's talk"
        sub="Questions, feedback or just need a hand? Send us a message and we'll get back within one business day."
      />
      <Container className="grid gap-6 py-[clamp(48px,7vw,96px)] lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] lg:gap-10">
        <ul className="m-0 grid list-none content-start gap-3 p-0">
          {[
            {
              icon: MapPin,
              b: "Registered office",
              s: <>Plot No 268, 1st Floor, Sector 33, Subhash Chowk, Islampur, Gurgaon, Haryana – 122001</>,
            },
            { icon: Phone, b: "Phone", s: <>{BRAND.phone} · {BRAND.hours}</> },
            { icon: Mail, b: "Email", s: <>{BRAND.email}</> },
            { icon: ShieldAlert, b: "Report fraud", s: <>{BRAND.fraudEmail}. We never ask for advance fees.</> },
          ].map(({ icon: Icon, b, s }) => (
            <li key={b} className="lp-card flex items-start gap-4 rounded-[22px] p-5">
              <IconTile size={46} className="text-gold-dark">
                <Icon aria-hidden size={20} strokeWidth={1.9} />
              </IconTile>
              <span className="min-w-0 pt-0.5 leading-snug">
                <span className="block text-[15px] font-medium text-ink">{b}</span>
                <span className="mt-1 block break-words text-[14px] text-slate">{s}</span>
              </span>
            </li>
          ))}
        </ul>
        <ContactForm />
      </Container>
    </div>
  );
}

function ContactForm() {
  const [name, setName] = React.useState("");
  const [phone, setPhone] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [topic, setTopic] = React.useState(TOPICS[0]);
  const [message, setMessage] = React.useState("");
  const [communicationConsent, setCommunicationConsent] = React.useState(false);
  const [status, setStatus] = React.useState<Status>("idle");
  const [error, setError] = React.useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (status === "sending") return;
    if (!communicationConsent) {
      setError("Your consent is required to continue.");
      setStatus("error");
      return;
    }
    setStatus("sending");
    setError(null);
    try {
      const res = await fetch(`${config.apiBaseUrl}/contact`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name, phone, email, topic, message }),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        const msg =
          data?.error?.message ||
          data?.message ||
          "We couldn't send your message. Please try again in a moment.";
        setError(msg);
        setStatus("error");
        return;
      }
      setStatus("sent");
    } catch {
      setError("Couldn't reach our servers. Please check your connection and try again.");
      setStatus("error");
    }
  }

  function reset() {
    setName("");
    setPhone("");
    setEmail("");
    setTopic(TOPICS[0]);
    setMessage("");
    setCommunicationConsent(false);
    setError(null);
    setStatus("idle");
  }

  if (status === "sent") {
    return (
      <div className="lp-card rounded-[28px] p-8 text-center sm:p-11" role="status">
        <span aria-hidden className="mx-auto grid h-16 w-16 place-items-center rounded-full bg-success-50 text-success-700">
          <Check size={30} strokeWidth={2.5} />
        </span>
        <h2 className="lp-h3 mt-6 !text-[24px]">Message sent</h2>
        <p className="m-0 mx-auto mt-3 max-w-[420px] text-[15px] leading-[1.65] text-slate">
          Thanks for reaching out. Your query has reached our team. We&apos;ll get back to you as soon as possible, usually
          within one business day.
        </p>
        <button
          type="button"
          onClick={reset}
          className={cn("mt-7 inline-flex h-11 items-center rounded-full border border-[rgb(var(--c-line-2))] bg-paper px-5 text-[14px] font-semibold text-ink shadow-pill", FOCUS)}
        >
          Send another message
        </button>
      </div>
    );
  }

  const sending = status === "sending";

  return (
    <div className="lp-card rounded-[28px] p-6 sm:p-9">
      <h2 className="lp-h3 !text-[24px]">Send us a message</h2>
      <p className="m-0 mt-2 text-[14.5px] text-slate">We&apos;ll never share your details. Required fields are marked *.</p>
      <form onSubmit={onSubmit} noValidate className="mt-7 grid gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="c-name" className={LABEL}>
              Full name *
            </label>
            <input id="c-name" type="text" autoComplete="name" placeholder="Your name" value={name} onChange={(e) => setName(e.target.value)} required disabled={sending} className={FIELD} />
          </div>
          <div>
            <label htmlFor="c-phone" className={LABEL}>
              Phone *
            </label>
            <input id="c-phone" type="tel" autoComplete="tel" placeholder="+91" value={phone} onChange={(e) => setPhone(e.target.value)} required disabled={sending} className={FIELD} />
          </div>
        </div>
        <div>
          <label htmlFor="c-email" className={LABEL}>
            Email *
          </label>
          <input id="c-email" type="email" autoComplete="email" placeholder="you@email.com" value={email} onChange={(e) => setEmail(e.target.value)} required disabled={sending} className={FIELD} />
        </div>
        <div>
          <label htmlFor="c-topic" className={LABEL}>
            Topic
          </label>
          <select id="c-topic" value={topic} onChange={(e) => setTopic(e.target.value)} disabled={sending} className={cn(FIELD, "select-themed")}>
            {TOPICS.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="c-message" className={LABEL}>
            Message *
          </label>
          <textarea id="c-message" rows={4} placeholder="How can we help?" value={message} onChange={(e) => setMessage(e.target.value)} required disabled={sending} className={cn(FIELD, "resize-y")} />
        </div>
        <label className="flex cursor-pointer items-start gap-3 text-[13.5px] leading-[1.55] text-ink">
          <input
            type="checkbox"
            checked={communicationConsent}
            onChange={(event) => setCommunicationConsent(event.target.checked)}
            disabled={sending}
            className="mt-0.5 h-[18px] w-[18px] shrink-0 accent-[rgb(var(--c-gold-600))]"
          />
          <span>{COMMUNICATION_CONSENT_TEXT}</span>
        </label>
        <p className="m-0 text-[12.5px] leading-[1.55] text-slate">{COMMUNICATION_PRIVACY_TEXT}</p>
        {error && (
          <p role="alert" className="m-0 text-[13.5px] leading-[1.5] text-error-700">
            {error}
          </p>
        )}
        <button
          type="submit"
          disabled={sending}
          className={cn(
            "group mt-1 inline-flex h-[52px] w-full items-center justify-center gap-2 rounded-full bg-gold text-[15px] font-semibold text-ink shadow-gold transition-[background-color,transform] duration-300 hover:-translate-y-px hover:bg-gold-400 disabled:opacity-70",
            FOCUS,
          )}
        >
          {sending ? (
            "Sending…"
          ) : (
            <>
              Send message <ArrowRight aria-hidden size={16} className="transition-transform group-hover:translate-x-0.5" />
            </>
          )}
        </button>
      </form>
    </div>
  );
}
