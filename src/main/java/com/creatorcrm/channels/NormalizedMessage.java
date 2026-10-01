package com.creatorcrm.channels;

import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import java.time.OffsetDateTime;

/**
 * A message from any channel in one shape.
 *
 * @param threadKey    channel-specific conversation key (Gmail thread id, Instagram "ig:&lt;other party id&gt;")
 * @param counterparty address/id of the other party (brand) in this conversation
 * @param bulk         looks like bulk mail (List-Unsubscribe, Precedence: bulk); used by the pre-filter
 */
public record NormalizedMessage(
        Platform platform,
        String externalId,
        String threadKey,
        Direction direction,
        String sender,
        String senderName,
        String recipient,
        String counterparty,
        String subject,
        String content,
        String rfcMessageId,
        String replyTo,
        OffsetDateTime sentAt,
        boolean bulk) {}
