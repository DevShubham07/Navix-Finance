package com.navix.common.loan;

import java.util.Collection;
import java.util.Map;

/**
 * Port for the per-customer trust stars on staff list rows. Implemented in {@code navix-loan} and
 * consumed from {@code navix-collections} across this seam. Batched by contract: a constant number of
 * queries per call, whatever the page size. Customers with no data are absent or {@link TrustSignals#NONE}.
 */
public interface CustomerTrustDirectory {

    Map<Long, TrustSignals> forCustomers(Collection<Long> customerIds);
}
