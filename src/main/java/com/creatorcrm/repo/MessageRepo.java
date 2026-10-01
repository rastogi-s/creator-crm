package com.creatorcrm.repo;

import com.creatorcrm.domain.Message;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepo extends JpaRepository<Message, Long> {
    boolean existsByExternalId(String externalId);

    List<Message> findByConversationIdOrderBySentAtAsc(Long conversationId);

    List<Message> findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc();

    long countByAiProcessedFalseAndFilteredReasonIsNull();

    boolean existsByConversationIdAndSentAtAfter(Long conversationId, OffsetDateTime sentAt);

    boolean existsByConversationIdAndAiProcessedTrueAndSentAtAfter(Long conversationId, OffsetDateTime sentAt);
}
