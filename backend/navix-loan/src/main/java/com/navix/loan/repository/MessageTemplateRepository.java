package com.navix.loan.repository;

import com.navix.loan.entity.MessageTemplate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    List<MessageTemplate> findByChannelOrderByIdDesc(String channel);

    List<MessageTemplate> findByStatusAndSystemManagedFalse(String status);

    boolean existsByChannelAndName(String channel, String name);

    boolean existsByChannelAndProviderId(String channel, String providerId);
}
