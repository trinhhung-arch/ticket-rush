package com.ticketrush.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class TicketTokensTest {

    private final TicketTokens tokens = new TicketTokens(new TicketProperties("unit-test-key-0123456789-0123456789"));

    @Test
    void acceptsItsOwnTokens() {
        UUID ticketId = UUID.randomUUID();

        assertThat(tokens.verify(tokens.sign(ticketId))).contains(ticketId);
    }

    @Test
    void rejectsForgedAndTamperedTokens() {
        String genuine = tokens.sign(UUID.randomUUID());
        String otherTicket = UUID.randomUUID() + genuine.substring(genuine.indexOf('.'));
        String signedWithAnotherKey = new TicketTokens(new TicketProperties("another-key-0123456789-0123456789"))
                .sign(UUID.randomUUID());

        assertThat(tokens.verify(otherTicket)).isEmpty();
        assertThat(tokens.verify(signedWithAnotherKey)).isEmpty();
        assertThat(tokens.verify("not-a-token")).isEmpty();
        assertThat(tokens.verify(genuine + "x")).isEmpty();
    }

    @Test
    void refusesShortKeys() {
        assertThatThrownBy(() -> new TicketProperties("too-short")).isInstanceOf(IllegalArgumentException.class);
    }
}
