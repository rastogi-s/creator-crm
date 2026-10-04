package com.creatorcrm.repo;

import com.creatorcrm.domain.Message;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MessageRepo extends JpaRepository<Message, Long> {
    boolean existsByExternalId(String externalId);

    /** {@code pattern} is a lower-case LIKE pattern with a backslash as the escape character. Newest first. */
    @Query("select m from Message m where m.conversationId is not null and (lower(m.content) like ?1 escape '\\'"
            + " or lower(m.subject) like ?1 escape '\\' or lower(m.sender) like ?1 escape '\\'"
            + " or lower(m.senderName) like ?1 escape '\\') order by m.sentAt desc")
    List<Message> search(String pattern, Pageable page);

    List<Message> findByConversationIdOrderBySentAtAsc(Long conversationId);

    List<Message> findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc();

    long countByAiProcessedFalseAndFilteredReasonIsNull();

    boolean existsByConversationIdAndSentAtAfter(Long conversationId, OffsetDateTime sentAt);

    boolean existsByConversationIdAndAiProcessedTrueAndSentAtAfter(Long conversationId, OffsetDateTime sentAt);
}
