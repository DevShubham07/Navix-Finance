/**
 * A staffer's last view of a register — its filters, search, sort and page size — per staffer, per
 * register.
 *
 * <p>Deliberately its own module with no imports, for the same reason as {@link ./search-recents}:
 * the sign-out path clears these, and pulling the register machinery into the auth bundle to reach
 * one localStorage key would be silly.
 *
 * <p>What is stored is the **serialised query string**, not a JSON blob of state. One format for the
 * URL, the server and this, so there is one parser and one escaping rule rather than three that
 * drift — and restoring a view is then the same code path as opening a shared link.
 *
 * <p>A register view can name a borrower (a search term, a filtered employer), which is the same
 * exposure as the controls the staffer typed into. It never holds a result row.
 */

/** Per-staffer, so a shared console never restores one operator's view for the next. */
export function registerViewKey(staffId: string, registerId: string): string {
  return `navix-register-view:${staffId}:${registerId}`;
}

export function readRegisterView(staffId: string, registerId: string): string | null {
  try {
    const raw = localStorage.getItem(registerViewKey(staffId, registerId));
    return raw && raw.length <= 4096 ? raw : null;
  } catch {
    // Private mode / blocked storage — a remembered view is a convenience, never a requirement,
    // so every accessor here fails soft and the register opens on its defaults.
    return null;
  }
}

export function writeRegisterView(staffId: string, registerId: string, queryString: string): void {
  try {
    if (queryString) localStorage.setItem(registerViewKey(staffId, registerId), queryString);
    else localStorage.removeItem(registerViewKey(staffId, registerId));
  } catch {
    // ignore — see above
  }
}

export function clearRegisterView(staffId: string, registerId: string): void {
  try {
    localStorage.removeItem(registerViewKey(staffId, registerId));
  } catch {
    // ignore — see above
  }
}

/** Every remembered view for one staffer — what sign-out clears. */
export function clearAllRegisterViews(staffId: string): void {
  try {
    const prefix = `navix-register-view:${staffId}:`;
    for (const key of Object.keys(localStorage)) {
      if (key.startsWith(prefix)) localStorage.removeItem(key);
    }
  } catch {
    // ignore — see above
  }
}
