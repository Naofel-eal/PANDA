package io.nud.infrastructure.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HashSupportTest {

    @Test
    @DisplayName("Given a ticket fingerprint when NUD hashes it then a stable SHA-256 identifier is produced")
    void givenTicketFingerprint_whenNUDHashesIt_thenStableSha256IdentifierIsProduced() {
        HashSupport hashSupport = new HashSupport();

        assertEquals("bf73a847ce24747b74c2338e998557e39f7ad5efb4d2bb845f662b6a3e397316", hashSupport.sha256("SCRUM-1"));
    }
}
