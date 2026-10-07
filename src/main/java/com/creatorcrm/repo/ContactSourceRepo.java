package com.creatorcrm.repo;

import com.creatorcrm.domain.ContactSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactSourceRepo extends JpaRepository<ContactSource, Long> {
    List<ContactSource> findByContactIdOrderByFoundAtAsc(Long contactId);

    List<ContactSource> findByContactIdIn(Collection<Long> contactIds);

    void deleteByContactId(Long contactId);
}
