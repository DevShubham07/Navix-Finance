package com.navix.loan.bre;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.LoanRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The blocked pin code list (V105) behind BRE rule {@link BreRuleCatalog#PINCODE_BLOCKED_REJECT}: the lookup
 * the verification steps use, and the ADMIN edits on the BRE rules tab.
 */
@Service
@RequiredArgsConstructor
public class BlockedPincodeService {

    private static final Logger log = LoggerFactory.getLogger(BlockedPincodeService.class);
    private static final Pattern PIN = Pattern.compile("[1-9][0-9]{5}");
    private static final int SEARCH_LIMIT = 50;

    public record PincodeList(long total, List<String> matches) {
    }

    private final JdbcTemplate jdbc;
    private final LoanRepository loanRepository;

    /** The pin as stored (6 digits), or null when the value is not a pin code. Spaces are dropped ("110 001"). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("\\s", "");
        return PIN.matcher(s).matches() ? s : null;
    }

    /** {@code raw} normalised, when it is on the blocked list; else null. */
    @Transactional(readOnly = true)
    public String blockedOf(String raw) {
        String pin = normalize(raw);
        if (pin == null) {
            return null;
        }
        Boolean hit = jdbc.queryForObject("select exists(select 1 from blocked_pincode where pincode = ?)",
                Boolean.class, pin);
        return Boolean.TRUE.equals(hit) ? pin : null;
    }

    /** The rule targets new customers only: not a reborrow and no loan ever booked for this customer. */
    @Transactional(readOnly = true)
    public boolean isNewCustomer(LoanApplication app) {
        return app.getReappliedFrom() == null && loanRepository.findByCustomerId(app.getCustomerId()).isEmpty();
    }

    // ---------------------------------------------------------------- ADMIN list management

    @Transactional(readOnly = true)
    public PincodeList search(String q) {
        requireAdmin();
        String prefix = q == null ? "" : q.replaceAll("\\D", "");
        List<String> matches = jdbc.queryForList(
                "select pincode from blocked_pincode where pincode like ? order by pincode limit " + SEARCH_LIMIT,
                String.class, prefix + "%");
        return new PincodeList(total(), matches);
    }

    @Transactional
    public PincodeList add(Collection<String> raw) {
        String by = requireAdmin().id();
        Set<String> pins = validated(raw);
        jdbc.batchUpdate("insert into blocked_pincode (pincode, created_by) values (?, ?) on conflict do nothing",
                pins.stream().map(p -> new Object[] {p, by}).toList());
        log.info("blocked pin codes: {} added by {}", pins.size(), by);
        return new PincodeList(total(), List.copyOf(pins));
    }

    @Transactional
    public PincodeList remove(String raw) {
        String by = requireAdmin().id();
        String pin = normalize(raw);
        if (pin == null) {
            throw new BusinessException("INVALID_PINCODE", "A pin code is 6 digits and does not start with 0");
        }
        jdbc.update("delete from blocked_pincode where pincode = ?", pin);
        log.info("blocked pin code {} removed by {}", pin, by);
        return new PincodeList(total(), List.of());
    }

    /** Replace the whole list (CSV upload). An empty upload is refused so a bad file can't wipe the list. */
    @Transactional
    public PincodeList replace(Collection<String> raw) {
        String by = requireAdmin().id();
        Set<String> pins = validated(raw);
        jdbc.update("delete from blocked_pincode");
        jdbc.batchUpdate("insert into blocked_pincode (pincode, created_by) values (?, ?)",
                pins.stream().map(p -> new Object[] {p, by}).toList());
        log.info("blocked pin code list replaced with {} pins by {}", pins.size(), by);
        return new PincodeList(total(), List.of());
    }

    private long total() {
        Long n = jdbc.queryForObject("select count(*) from blocked_pincode", Long.class);
        return n == null ? 0 : n;
    }

    private static Set<String> validated(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException("PINCODES_REQUIRED", "Send at least one pin code");
        }
        Set<String> pins = new LinkedHashSet<>();
        for (String r : raw) {
            String pin = normalize(r);
            if (pin == null) {
                throw new BusinessException("INVALID_PINCODE", "Not a pin code: " + r);
            }
            pins.add(pin);
        }
        return pins;
    }

    private static CurrentActor requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
        return actor;
    }
}
