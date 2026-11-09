package com.navix.loan.repository;

import com.navix.loan.entity.CustomerMessage;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerMessageRepository extends JpaRepository<CustomerMessage, Long> {

    /** Duplicate guard: same customer + template + channel already SENT since {@code since}. */
    boolean existsByCustomerIdAndTemplateKeyAndChannelAndStatusAndCreatedAtAfter(
            Long customerId, String templateKey, String channel, String status, Instant since);

    /** Null filters are ignored; the date window is always bounded by the caller. */
    @Query("select m from CustomerMessage m where (:customerId is null or m.customerId = :customerId) "
            + "and (:templateKey is null or m.templateKey = :templateKey) "
            + "and (:channel is null or m.channel = :channel) "
            + "and (:status is null or m.status = :status) "
            + "and m.createdAt >= :from and m.createdAt < :to order by m.createdAt desc, m.id desc")
    Page<CustomerMessage> search(@Param("customerId") Long customerId, @Param("templateKey") String templateKey,
                                 @Param("channel") String channel, @Param("status") String status,
                                 @Param("from") Instant from, @Param("to") Instant to, Pageable page);
}
