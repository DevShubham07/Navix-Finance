import { redirect } from "next/navigation";

/** Preserve old bookmarks while keeping signing inside the agreement page. */
export default function EsignPage() {
  redirect("/loan/sanction-letter");
}
