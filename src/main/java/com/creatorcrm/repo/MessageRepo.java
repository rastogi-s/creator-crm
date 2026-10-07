package com.creatorcrm.repo;

import com.creatorcrm.domain.Message;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MessageRepo extends JpaRepository<Message, Long> {
    boolean existsByExternalId(String externalId);

    java.util.Optional<Message> findByExternalId(String externalId);

    /** {@code pattern} is a lower-case LIKE pattern with a backslash as the escape character. Newest first. */
    @Query("select m from Message m where m.conversationId is not null and (lower(m.content) like ?1 escape '\\'"
            + " or lower(m.subject) like ?1 escape '\\' or lower(m.sender) like ?1 escape '\\'"
            + " or lower(m.senderName) like ?1 escape '\\') order by m.sentAt desc")
    List<Message> search(String pattern, Pageable page);

    List<Message> findByConversationIdOrderBySentAtAsc(Long conversationId);

    /** New messages since the campaign watcher last looked. */
    List<Message> findTop500ByIdGreaterThanOrderByIdAsc(Long id);

    @Query("select max(m.id) from Message m")
    Long maxId();

    List<Message> findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc();

    long countByAiProcessedFalseAndFilteredReasonIsNull();

    boolean existsByConversationIdAndSentAtAfter(Long conversationId, OffsetDateTime sentAt);

    boolean existsByConversationIdAndAiProcessedTrueAndSentAtAfter(Long conversationId, OffsetDateTime sentAt);

    /** Only the HTML column, so a sync updating the same message at the same moment isn't overwritten. */
    @Modifying
    @Transactional
    @Query("update Message m set m.htmlContent = ?2 where m.id = ?1")
    int saveHtml(Long id, String html);
}
