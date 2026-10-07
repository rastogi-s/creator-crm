package com.creatorcrm.repo;

import com.creatorcrm.domain.ContactList;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactListRepo extends JpaRepository<ContactList, Long> {
    List<ContactList> findAllByOrderByNameAsc();
}
