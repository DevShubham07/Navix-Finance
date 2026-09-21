package com.navix.app.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.app.search.SearchQuery.Kind;
import org.junit.jupiter.api.Test;

/**
 * Ops paste identifiers in whatever shape the source document used. Every one of these spellings has
 * to reach the same needle, or the same person is "not found" depending on where their number was
 * copied from.
 */
class SearchQueryTest {

    @Test
    void readsEveryMobileSpellingAsTheSameTenDigits() {
        for (String raw : new String[] {"9876543210", "+919876543210", "919876543210",
                "+91 98765 43210", " 98765 43210 "}) {
            SearchQuery q = SearchQuery.parse(raw);
            assertThat(q.kind()).as(raw).isEqualTo(Kind.MOBILE);
            assertThat(q.needle()).as(raw).isEqualTo("9876543210");
        }
    }

    @Test
    void upperCasesAPan() {
        SearchQuery q = SearchQuery.parse("abcde1234f");
        assertThat(q.kind()).isEqualTo(Kind.PAN);
        assertThat(q.needle()).isEqualTo("ABCDE1234F");
    }

    @Test
    void treatsAShortNumberAsAnIdWithOrWithoutTheHashShorthand() {
        assertThat(SearchQuery.parse("#1042").kind()).isEqualTo(Kind.ID);
        assertThat(SearchQuery.parse("#1042").needle()).isEqualTo("1042");
        assertThat(SearchQuery.parse("1042").kind()).isEqualTo(Kind.ID);
    }

    @Test
    void aTenDigitNumberStartingBelowSixIsAnIdNotAMobile() {
        // Indian mobiles start 6-9; anything else that long is an id someone pasted.
        assertThat(SearchQuery.parse("1234567890").kind()).isEqualTo(Kind.ID);
    }

    @Test
    void collapsesWhitespaceInFreeText() {
        SearchQuery q = SearchQuery.parse("  Rajesh   Kumar ");
        assertThat(q.kind()).isEqualTo(Kind.TEXT);
        assertThat(q.needle()).isEqualTo("Rajesh Kumar");
    }

    @Test
    void needsTwoCharactersOfTextButOnlyOneDigit() {
        assertThat(SearchQuery.parse("").searchable()).isFalse();
        assertThat(SearchQuery.parse("r").searchable()).isFalse();
        assertThat(SearchQuery.parse("ra").searchable()).isTrue();
        assertThat(SearchQuery.parse("7").searchable()).isTrue();
        assertThat(SearchQuery.parse("#7").searchable()).isTrue();
    }
}
