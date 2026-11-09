package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.query.ColumnFilter.Kind;
import com.navix.common.query.FilterSpec;
import com.navix.loan.service.LeadBookQuery.LeadFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** The SQL the register runs, without a database: scope first, boundaries half-open, scope not filterable. */
class LeadBookQueryTest {

    private static final LeadFilter NONE = new LeadFilter(null, null, null, null, null, null, null, null, null);

    /** The DSA exclusion is the FIRST clause of every statement, filters or not. */
    @Test
    void dsaExclusionLeadsTheWhere_withAndWithoutFilters() {
        FilterSpec spec = FilterSpec.parse(Map.of("f.source", "in:DSA"), LeadBookQuery.FILTER_COLUMNS);

        assertThat(LeadBookQuery.where(NONE, FilterSpec.empty(), new MapSqlParameterSource()))
                .isEqualTo(" where l.owner_dsa_id is null");
        assertThat(LeadBookQuery.where(NONE, spec, new MapSqlParameterSource()))
                .startsWith(" where l.owner_dsa_id is null and (");
    }

    /** A request cannot argue with the scope: the owner column is not a registered filter, however it is spelled. */
    @Test
    void ownerColumnIsNotAFilter() {
        for (String key : new String[] {"ownerDsaId", "owner_dsa_id", "l.owner_dsa_id"}) {
            assertThat(LeadBookQuery.FILTER_COLUMNS.get(key)).isNull();
        }
        FilterSpec spec = FilterSpec.parse(
                Map.of("f.ownerDsaId", "in:__NULL__,7", "f.owner_dsa_id", "num:0.."), LeadBookQuery.FILTER_COLUMNS);
        assertThat(spec.isEmpty()).isTrue();
    }

    /** Identity columns are text-only, so no dropdown of names, mobiles or pincodes can be built. */
    @Test
    void identityColumnsNeverFacet() {
        for (String key : new String[] {"name", "mobile", "pincode", "city"}) {
            assertThat(LeadBookQuery.FILTER_COLUMNS.get(key).accepts(Kind.IN)).as(key).isFalse();
        }
        assertThat(LeadBookQuery.FILTER_COLUMNS.get("source").accepts(Kind.IN)).isTrue();
        assertThat(LeadBookQuery.FILTER_COLUMNS.get("callStatus").accepts(Kind.IN)).isTrue();
    }

    /** Window half-open [from, to); ratings inclusive — bound as parameters, never concatenated. */
    @Test
    void windowAndRatingBoundaries() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        Instant from = Instant.parse("2026-07-13T00:00:00Z");
        Instant to = Instant.parse("2026-08-13T00:00:00Z");

        String where = LeadBookQuery.where(
                new LeadFilter(null, null, null, null, from, to, 2, 4, null), FilterSpec.empty(), p);

        assertThat(where).contains("l.created_at >= :from").contains("l.created_at < :to")
                .contains("l.quality_rating >= :minRating").contains("l.quality_rating <= :maxRating");
        assertThat(p.getValue("from")).isEqualTo(Timestamp.from(from));
        assertThat(p.getValue("to")).isEqualTo(Timestamp.from(to));
        assertThat(p.getValue("minRating")).isEqualTo(2);
        assertThat(p.getValue("maxRating")).isEqualTo(4);
    }

    /** LIKE wildcards in the search are literals, not patterns. */
    @Test
    void searchEscapesLikeWildcards() {
        MapSqlParameterSource p = new MapSqlParameterSource();
        LeadBookQuery.where(new LeadFilter("50%_Off", null, null, null, null, null, null, null, null),
                FilterSpec.empty(), p);
        assertThat(p.getValue("needleName")).isEqualTo("%50\\%\\_off%");
        assertThat(p.getValue("needleMobile")).isEqualTo("%50\\%\\_Off%");
    }
}
