package com.creatorcrm.rebook;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.domain.Enums.OpportunityStatus;
import org.junit.jupiter.api.Test;

/** Deal history from before and after status names lost their emoji both count. */
class WinBackHistoryTest {

    @Test
    void matchesOldAndNewStatusText() {
        assertThat(WinBack.endsWithStatus("Glow: 📦 Product Received → ✅ Posted", OpportunityStatus.POSTED)).isTrue();
        assertThat(WinBack.endsWithStatus("Glow: Product received → Posted", OpportunityStatus.POSTED)).isTrue();
        assertThat(WinBack.endsWithStatus("Glow: ✅ Posted → 💵 Payment Pending", OpportunityStatus.PAYMENT_PENDING)).isTrue();
        assertThat(WinBack.endsWithStatus("Glow: Posted → Cold", OpportunityStatus.POSTED)).isFalse();
    }
}
