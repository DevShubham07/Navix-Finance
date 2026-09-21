package com.navix.app.search;

import java.util.List;
import java.util.Map;

/** Wire shape for the staff global-search palette ({@code GET /api/staff/search}). */
public final class GlobalSearchDtos {

    private GlobalSearchDtos() {
    }

    /**
     * One hit.
     *
     * <p>{@code meta} carries money in <b>paise</b> (the client formats it — the backend never sends
     * a rupee string). Mobile and PAN arrive <b>masked</b>: the palette is glanceable from across a
     * desk, and the full value is always one keystroke away on the record itself.
     *
     * @param href where selecting the row navigates; already correct for the caller's role.
     */
    public record SearchItem(
            String kind,
            String id,
            String title,
            String subtitle,
            Map<String, Object> meta,
            String href,
            String badge) {
    }

    /**
     * A family of hits. A group the caller's role cannot see is <b>absent</b> from the response, never
     * present-and-empty: "no results in Customers" and "you may not search customers" must not look
     * the same, or the palette becomes an existence oracle.
     *
     * @param more the server capped this group — drives the "View all" link to the full list page.
     */
    public record SearchGroup(String kind, String label, List<SearchItem> items, boolean more) {
    }

    /** @param interpretedAs how {@link SearchQuery} read the raw input: mobile | pan | id | text. */
    public record SearchResponse(String query, String interpretedAs, List<SearchGroup> groups) {
    }
}
