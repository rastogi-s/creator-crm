package com.creatorcrm.repo;

import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Platform;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepo extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByPlatformAndExternalId(Platform platform, String externalId);
}
