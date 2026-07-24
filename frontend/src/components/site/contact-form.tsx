"use client";

import * as React from "react";
import { config } from "@/lib/config";

const TOPICS = ["General enquiry", "Application help", "Repayment", "Grievance", "Report fraud"];

type Status = "idle" | "sending" | "sent" | "error";

/**
 * The live "Contact us" section for the marketing site. Reproduces the design-export markup (so it
 * shares the `.navix-mkt` styling + scroll-reveal) but wires the form to the BFF `/api/contact`
 * endpoint, which emails the enquiry to the DhanBoost support inbox. On success it swaps the form for a
 * confirmation that the query has been sent and will be answered as soon as possible.
 *
 * Rendered as one client component (rather than the usual static `MarketingHtml` blob) so the form is
 * interactive; the whole `<section>` lives here to keep the `.contact-grid` two-column layout intact.
 */
export function ContactSection() {
  return (
    <section className="page active" id="contact">
      <div className="page-hero">
        <div className="wrap">
          <div className="crumb">
            {/* eslint-disable-next-line @next/next/no-html-link-for-pages -- design SPA-nav anchor, intercepted by MarketingScripts */}
            <a href="/" data-link="">
              Home
            </a>{" "}
            &nbsp;/&nbsp; Contact
          </div>
          <h1>Let&apos;s talk</h1>
          <p>
            Questions, feedback or just need a hand? Send us a message and we&apos;ll get back within one
            business day.
          </p>
        </div>
      </div>

      <div className="sec">
        <div className="wrap">
          <div className="contact-grid">
            <div className="cinfo reveal">
              <div className="ci-card">
                <span className="ci-ico">
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z" />
                    <circle cx="12" cy="10" r="3" />
                  </svg>
                </span>
                <div>
                  <b>Registered office</b>
                  <small>Dev Nagar, Gurugram – 122102</small>
                </div>
              </div>
              <div className="ci-card">
                <span className="ci-ico">
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z" />
                  </svg>
                </span>
                <div>
                  <b>Phone</b>
                  <small>+91 97167 60246 · Mon–Sat, 9:30 AM–6:30 PM</small>
                </div>
              </div>
              <div className="ci-card">
                <span className="ci-ico">
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <rect x="2" y="4" width="20" height="16" rx="2" />
                    <path d="m22 7-10 5L2 7" />
                  </svg>
                </span>
                <div>
                  <b>Email</b>
                  <small>info@dhanboost.com</small>
                </div>
              </div>
              <div className="ci-card">
                <span className="ci-ico">
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M12 2 4 5v6c0 5 3.5 9 8 11 4.5-2 8-6 8-11V5l-8-3z" />
                  </svg>
                </span>
                <div>
                  <b>Report fraud</b>
                  <small>info@dhanboost.com — we never ask for advance fees.</small>
                </div>
              </div>
            </div>
            <ContactForm />
          </div>
        </div>
      </div>
    </section>
  );
}

function ContactForm() {
  const [name, setName] = React.useState("");
  const [phone, setPhone] = React.useState("");
  const [email, setEmail] = React.useState("");
  const [topic, setTopic] = React.useState(TOPICS[0]);
  const [message, setMessage] = React.useState("");
  const [status, setStatus] = React.useState<Status>("idle");
  const [error, setError] = React.useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (status === "sending") return;
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
    setError(null);
    setStatus("idle");
  }

  if (status === "sent") {
    return (
      <div className="formcard reveal d1">
        <div style={{ textAlign: "center", padding: "18px 4px" }}>
          <div
            aria-hidden
            style={{
              width: 62,
              height: 62,
              margin: "0 auto 20px",
              borderRadius: "50%",
              background: "rgba(46,160,90,.12)",
              color: "#2ea05a",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M20 6 9 17l-5-5" />
            </svg>
          </div>
          <h3 style={{ fontSize: "1.5rem", marginBottom: 8 }}>Message sent</h3>
          <p style={{ fontSize: ".95rem", lineHeight: 1.6, marginBottom: 24 }}>
            Thanks for reaching out — your query has reached our team. We&apos;ll get back to you as soon as
            possible, usually within one business day.
          </p>
          <button className="btn btn-ghost" type="button" onClick={reset}>
            Send another message
          </button>
        </div>
      </div>
    );
  }

  const sending = status === "sending";

  return (
    <div className="formcard reveal d1">
      <h3 style={{ fontSize: "1.5rem", marginBottom: 6 }}>Send us a message</h3>
      <p style={{ fontSize: ".92rem", marginBottom: 24 }}>
        We&apos;ll never share your details. Required fields are marked *.
      </p>
      <form onSubmit={onSubmit} noValidate>
        <div className="field-row">
          <div className="field">
            <label>Full name *</label>
            <input
              type="text"
              placeholder="Your name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              disabled={sending}
            />
          </div>
          <div className="field">
            <label>Phone *</label>
            <input
              type="tel"
              placeholder="+91"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              required
              disabled={sending}
            />
          </div>
        </div>
        <div className="field">
          <label>Email *</label>
          <input
            type="email"
            placeholder="you@email.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={sending}
          />
        </div>
        <div className="field">
          <label>Topic</label>
          <select value={topic} onChange={(e) => setTopic(e.target.value)} disabled={sending}>
            {TOPICS.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Message *</label>
          <textarea
            rows={4}
            placeholder="How can we help?"
            value={message}
            onChange={(e) => setMessage(e.target.value)}
            required
            disabled={sending}
          />
        </div>
        {error && (
          <p
            role="alert"
            style={{ fontSize: ".85rem", color: "#c0392b", margin: "0 0 14px", lineHeight: 1.5 }}
          >
            {error}
          </p>
        )}
        <button className="btn btn-gold btn-block btn-lg" type="submit" disabled={sending}>
          {sending ? (
            "Sending…"
          ) : (
            <>
              Send message{" "}
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                <path d="M5 12h14M12 5l7 7-7 7" />
              </svg>
            </>
          )}
        </button>
      </form>
    </div>
  );
}
