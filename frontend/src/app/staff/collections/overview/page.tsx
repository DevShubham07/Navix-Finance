/**
 * "DPD buckets (all)" was merged into the one DPD register at `/staff/collections`, whose default
 * scope is now All loans (and which can export). This redirect keeps old links and bookmarks alive.
 */
import { redirect } from "next/navigation";

export default function DpdOverviewPage() {
  redirect("/staff/collections");
}
