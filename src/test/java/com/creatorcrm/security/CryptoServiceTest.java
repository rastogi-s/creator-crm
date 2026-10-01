package com.creatorcrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsAndUsesFreshIvEachTime() {
        CryptoService c = new CryptoService(key());
        String a = c.encrypt("sk-ant-secret");
        String b = c.encrypt("sk-ant-secret");
        assertThat(a).isNotEqualTo(b).doesNotContain("sk-ant-secret");
        assertThat(c.decrypt(a)).isEqualTo("sk-ant-secret");
    }

    @Test
    void rejectsTamperedCiphertextAndWrongKey() {
        CryptoService c = new CryptoService(key());
        String ct = c.encrypt("token");
        char last = ct.charAt(ct.length() - 3);
        String tampered = ct.substring(0, ct.length() - 3) + (last == 'A' ? 'B' : 'A') + ct.substring(ct.length() - 2);
        assertThatThrownBy(() -> c.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CryptoService(key()).decrypt(ct)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constantTimeEqualsHandlesNulls() {
        assertThat(CryptoService.constantTimeEquals("a", "a")).isTrue();
        assertThat(CryptoService.constantTimeEquals("a", "b")).isFalse();
        assertThat(CryptoService.constantTimeEquals(null, "a")).isFalse();
    }
}
