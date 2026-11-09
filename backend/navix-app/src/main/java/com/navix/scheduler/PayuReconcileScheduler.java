package com.navix.scheduler;

import com.navix.loan.payu.PayuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reads PayU orders whose webhook or browser return never arrived. Deliberately NOT gated on the
 * {@code payu-repayment} flag: money already in flight must still be credited after the flag is turned off.
 * Single instance only, like every scheduler here (see SchedulingConfig).
 */
@Component
public class PayuReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayuReconcileScheduler.class);

    private final PayuService payu;

    public PayuReconcileScheduler(PayuService payu) {
        this.payu = payu;
    }

    @Scheduled(cron = "${navix.payu.reconcile-cron:0 */5 * * * *}")
    public void sweep() {
        try {
            int n = payu.reconcileDue();
            if (n > 0) {
                log.info("PayU reconcile: {} orders re-read", n);
            }
        } catch (RuntimeException e) {
            log.warn("PayU reconcile sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
