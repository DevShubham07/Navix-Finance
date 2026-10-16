package com.navix.common.query;

import static com.navix.common.query.ColumnFilter.Kind.*;

final class QueryTestSupport {
    private QueryTestSupport() {
    }

    static final FilterColumns COLS = FilterColumns.builder()
            .column("status", "b.status", IN)
            .column("officer", "b.owner_staff_id", IN)
            .column("amount", "b.amount", NUM)
            .column("dueDate", "b.due_date", DAY)
            .column("name", "lower(b.full_name)", TEXT, IN)
            .build();
}
