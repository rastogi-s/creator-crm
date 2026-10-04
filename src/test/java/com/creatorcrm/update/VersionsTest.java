package com.creatorcrm.update;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void comparesNumericallyNotAlphabetically() {
        assertThat(Versions.isNewer("1.10.0", "1.9.3")).isTrue();
        assertThat(Versions.isNewer("v1.1.0", "1.0.5")).isTrue();
        assertThat(Versions.isNewer("1.0.5", "1.0.5")).isFalse();
        assertThat(Versions.isNewer("1.0.4", "1.0.5")).isFalse();
    }

    @Test
    void aPreReleaseComesBeforeItsRelease() {
        assertThat(Versions.isNewer("1.1.0", "1.1.0-beta.1")).isTrue();
        assertThat(Versions.isNewer("1.1.0-beta.1", "1.1.0")).isFalse();
        assertThat(Versions.isNewer("1.1.0-beta.2", "1.1.0-beta.1")).isTrue();
    }

    @Test
    void garbageNeverCountsAsNewer() {
        assertThat(Versions.isNewer("latest", "1.0.0")).isFalse();
        assertThat(Versions.isValid("v1.2.3")).isTrue();
        assertThat(Versions.isValid("1.2")).isFalse();
    }

    @Test
    void nextMinor() {
        assertThat(Versions.nextMinor("1.2.5")).isEqualTo("1.3.0");
        assertThat(Versions.nextMinor("1.1.0-beta.1")).isEqualTo("1.2.0");
    }
}
