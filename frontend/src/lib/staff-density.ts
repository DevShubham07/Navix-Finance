export const STAFF_DESKTOP_DENSITY_CLASS = "staff-desktop-density";

export function applyStaffDesktopDensity(
  classList: Pick<DOMTokenList, "add" | "remove">,
): () => void {
  classList.add(STAFF_DESKTOP_DENSITY_CLASS);
  return () => classList.remove(STAFF_DESKTOP_DENSITY_CLASS);
}
