import type { LeadOutcome, LeadView } from "@/lib/api/applications";

/**
 * The lead side panel's single Save.
 *
 * It used to be two buttons ("Save disposition", then "Save outcome & note"), and the second always
 * sent both outcome fields. The outcome endpoint is PATCH-semantics (an omitted field is left
 * alone), so sending a field the staffer never touched could overwrite it — most visibly on a
 * CONFIRMED lead, whose picker falls back to NEW: editing only the DSA note also wrote
 * `leadOutcome: NEW` over whatever was stored. These helpers decide what the one Save sends.
 */

/** The picker value for a lead's outcome: CONFIRMED is derived, never settable, so it shows as NEW. */
export function settableOutcome(outcome: LeadOutcome): LeadOutcome {
  return outcome === "CONFIRMED" ? "NEW" : outcome;
}

export interface LeadOutcomePatch {
  leadOutcome?: LeadOutcome;
  dsaNote?: string;
}

/**
 * The body for the outcome PUT, holding only the fields the staffer changed, or `null` when
 * neither changed and the PUT should not be sent at all.
 *
 * Notes compare trimmed, because the server trims before storing — re-saving "  hi " over "hi" is
 * not a change. A note cleared to blank is a change, sent as `""`, which the endpoint stores as
 * "no note".
 */
export function leadOutcomePatch(
  lead: Pick<LeadView, "leadOutcome" | "dsaNote">,
  outcome: LeadOutcome,
  dsaNote: string,
): LeadOutcomePatch | null {
  const patch: LeadOutcomePatch = {};
  if (outcome !== settableOutcome(lead.leadOutcome)) patch.leadOutcome = outcome;
  const note = dsaNote.trim();
  if (note !== (lead.dsaNote ?? "").trim()) patch.dsaNote = note;
  return Object.keys(patch).length > 0 ? patch : null;
}

/**
 * The one success toast for a Save, naming only what the server confirmed: the disposition if its
 * PUT succeeded, and from the outcome PUT only the field(s) it actually carried — an outcome the
 * staffer never touched was not sent, so it is not "saved". `savedPatch` is the body of an outcome
 * PUT that succeeded, or `null` when none was sent or it failed. `null` when nothing succeeded (each
 * failure is shown inline under the button instead).
 */
export function leadSaveToast(
  dispositionSaved: boolean,
  savedPatch: LeadOutcomePatch | null,
): string | null {
  const parts: string[] = [];
  if (dispositionSaved) parts.push("disposition");
  if (savedPatch?.leadOutcome !== undefined) parts.push("outcome");
  if (savedPatch?.dsaNote !== undefined) parts.push("DSA note");
  if (parts.length === 0) return null;
  const list =
    parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(", ")} & ${parts[parts.length - 1]}`;
  return `${list.charAt(0).toUpperCase()}${list.slice(1)} saved`;
}
