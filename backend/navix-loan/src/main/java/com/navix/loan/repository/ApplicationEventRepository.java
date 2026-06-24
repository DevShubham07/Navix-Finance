package com.navix.loan.repository;

import com.navix.loan.entity.ApplicationEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the application approval/audit trail. */
@Repository
public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    List<ApplicationEvent> findByApplicationIdOrderByAtAsc(Long applicationId);
}
