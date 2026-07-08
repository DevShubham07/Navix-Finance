import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * Merge conditional class names and de-duplicate conflicting Tailwind classes.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}

/**
 * Format a number as Indian Rupees (INR), e.g. 10000 -> "₹10,000.00".
 */
export function formatINR(amount: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
  }).format(amount);
}

/**
 * Format a number as whole-rupee INR, e.g. 200000 -> "₹2,00,000".
 * Matches the design's amount style (no paise).
 */
export function formatINR0(amount: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(amount);
}

/**
 * Normalise any user-entered Indian mobile to **at most 10 subscriber digits**.
 *
 * Strips spaces, dashes and brackets, drops a `+91` / `91` country code and any
 * leading STD `0`, then caps at 10 digits — so pasting `+91-98765 43210`,
 * `+919876543210` or `098765 43210` all collapse to `9876543210`. A genuine
 * 10-digit number that happens to start `91` is left untouched (only a 91 that
 * makes the string longer than 10 is treated as a country code).
 */
export function normalizeMobile(raw: string): string {
  let digits = (raw ?? "").replace(/\D/g, "");
  if (digits.length > 10 && digits.startsWith("91")) digits = digits.slice(2);
  digits = digits.replace(/^0+/, "");
  return digits.slice(0, 10);
}

/**
 * Format a date (Date | ISO string) as a human-readable Indian date.
 *
 * Date-only — use this for true calendar dates (e.g. the salary-linked loan due
 * date), where a clock time would be misleading. For event/audit timestamps and
 * activity logs use {@link formatDateTime}.
 */
export function formatDate(date: Date | string): string {
  const d = typeof date === "string" ? new Date(date) : date;
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(d);
}

/**
 * Format a timestamp (Date | ISO string) as an Indian date **and** time, e.g.
 * "24 Jun 2026, 02:30 pm". Use for event/maker-checker trails, collection logs,
 * settlement/approval/invite times — anything that happened at a moment, not a
 * pure calendar date.
 */
export function formatDateTime(date: Date | string): string {
  const d = typeof date === "string" ? new Date(date) : date;
  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  }).format(d);
}

/** Humanize a verification check type: "PENNY_DROP" → "Penny drop". */
export function humanizeCheck(checkType: string): string {
  return checkType
    .toLowerCase()
    .split(/[_\s]+/)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}
