package com.navix.common.util;

/**
 * The one {@code escapeLike}. It used to live as two private copies (CustomerBookQuery with
 * {@code \}, MetricQueries with {@code !}) that had already drifted; the escape character is now an
 * explicit argument, so each caller keeps its exact previous behaviour and the SQL's
 * {@code escape '…'} clause must name the same character.
 */
public final class LikeEscape {

    private LikeEscape() {
    }

    /** Escapes {@code % _} and the escape character itself, for {@code like … escape '\'}. */
    public static String escapeLike(String s) {
        return escapeLike(s, '\\');
    }

    public static String escapeLike(String s, char escape) {
        String e = String.valueOf(escape);
        return s.replace(e, e + e).replace("%", e + "%").replace("_", e + "_");
    }
}
