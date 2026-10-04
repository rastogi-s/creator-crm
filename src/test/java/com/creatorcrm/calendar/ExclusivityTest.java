package com.creatorcrm.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.calendar.Exclusivity.Window;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** When two deals' exclusivity windows or dates clash, and what each deal says about the other. */
class ExclusivityTest {

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    private static Window exclusive(long id, long brand, String name, LocalDate from, int months, boolean booked, String terms) {
        return new Window(id, brand, name, from, from.plusMonths(months), true, booked, terms);
    }

    private static Window dated(long id, long brand, String name, LocalDate day, boolean booked) {
        return new Window(id, brand, name, day, day, false, booked, "");
    }

    @Test
    void overlappingExclusivityClashesBothWays() {
        Window tea = exclusive(1, 10, "Bloomleaf Tea", OCT_1, 2, true, "No other tea or coffee brands");
        Window home = exclusive(2, 20, "Nova Nest Home", OCT_1.plusDays(5), 1, true, "");
        assertThat(Exclusivity.clash(tea, home)).isTrue();
        assertThat(Exclusivity.clash(home, tea)).isTrue();
        assertThat(Exclusivity.overlap(home, tea).text())
                .isEqualTo("Your exclusivity with Bloomleaf Tea (1 Oct to 1 Dec) overlaps this deal's (6 Oct to 6 Nov)."
                        + " Bloomleaf Tea's contract: No other tea or coffee brands. Check the two brands don't compete.");
        assertThat(Exclusivity.overlap(tea, home).otherBrand()).isEqualTo("Nova Nest Home");
    }

    @Test
    void anotherBrandsPostingDateInsideTheWindowClashes() {
        Window tea = exclusive(1, 10, "Bloomleaf Tea", OCT_1, 2, true, "");
        Window coffee = dated(3, 30, "Coastline Coffee", OCT_1.plusDays(20), false);
        assertThat(Exclusivity.clash(tea, coffee)).isTrue();
        assertThat(Exclusivity.overlap(coffee, tea).text()).startsWith("This deal's date (21 Oct) falls inside your exclusivity with Bloomleaf Tea (1 Oct to 1 Dec).");
        assertThat(Exclusivity.overlap(tea, coffee).text()).startsWith("Coastline Coffee's date (21 Oct) falls inside this deal's exclusivity (1 Oct to 1 Dec).");
    }

    @Test
    void noClashWhenNothingOverlapsOrNothingIsAtStake() {
        Window tea = exclusive(1, 10, "Bloomleaf Tea", OCT_1, 2, true, "");
        assertThat(Exclusivity.clash(tea, dated(2, 20, "Later", OCT_1.plusMonths(2).plusDays(1), true))).isFalse(); // after it ends
        assertThat(Exclusivity.clash(tea, dated(2, 20, "Ends day", OCT_1.plusMonths(2), true))).isTrue(); // last day counts
        assertThat(Exclusivity.clash(tea, exclusive(2, 10, "Bloomleaf again", OCT_1, 1, true, ""))).isFalse(); // same brand
        assertThat(Exclusivity.clash(tea, tea)).isFalse();
        // Neither is exclusive, or neither is booked: two leads she hasn't said yes to don't warn.
        assertThat(Exclusivity.clash(dated(1, 10, "A", OCT_1, true), dated(2, 20, "B", OCT_1, true))).isFalse();
        assertThat(Exclusivity.clash(exclusive(1, 10, "A", OCT_1, 1, false, ""), exclusive(2, 20, "B", OCT_1, 1, false, ""))).isFalse();
    }
}
