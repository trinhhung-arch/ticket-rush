package com.ticketrush.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.ticketrush.security.Roles;
import com.ticketrush.security.TestJwts;
import com.ticketrush.testing.RedisContainerConfiguration;
import com.ticketrush.waitingroom.domain.QueueStatus;
import com.ticketrush.waitingroom.domain.QueueStatus.State;
import com.ticketrush.waitingroom.domain.WaitingRoom;

/** One place in the room and 2-second admissions, so the queue moves within the test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ticketrush.waiting-room.capacity=1",
        "ticketrush.waiting-room.admission-ttl=PT2S",
        "ticketrush.waiting-room.admit-interval=PT0.2S",
        "ticketrush.waiting-room.token-key=" + WaitingRoomIntegrationTest.KEY,
        "ticketrush.security.load-test.secret=" + TestJwts.LOAD_TEST_SECRET})
@Import(RedisContainerConfiguration.class)
class WaitingRoomIntegrationTest {

    static final String KEY = "test-admission-key-0123456789-0123456789";

    @Autowired
    WaitingRoom room;

    @Value("${local.server.port}")
    int port;

    /** FR-WR-01: the first comer walks in, the rest leave in the order they arrived. */
    @Test
    void buyersAreLetInFirstComeFirstServed() throws Exception {
        UUID eventId = UUID.randomUUID();

        QueueStatus first = room.join(eventId, "an");
        assertThat(first.state()).isEqualTo(State.ADMITTED);
        assertThat(room.join(eventId, "binh").position()).isEqualTo(1);
        assertThat(room.join(eventId, "chi").position()).isEqualTo(2);
        assertThat(room.join(eventId, "binh").position()).as("joining again keeps the place").isEqualTo(1);
        assertThat(room.status(eventId, "chi").estimatedWaitSeconds()).as("upper bound: 2 rounds of 2 s").isEqualTo(4);
        assertThat(room.status(eventId, "dung").state()).isEqualTo(State.NOT_IN_QUEUE);

        await().atMost(Duration.ofSeconds(10)).until(() -> room.status(eventId, "binh").state() == State.ADMITTED);
        assertThat(room.status(eventId, "chi")).extracting(QueueStatus::state, QueueStatus::position)
                .containsExactly(State.QUEUED, 1L);
        await().atMost(Duration.ofSeconds(10)).until(() -> room.status(eventId, "chi").state() == State.ADMITTED);
    }

    /** FR-WR-03: the token names this buyer and this event, and is signed with the shared key. */
    @Test
    void admissionTokenIsBoundToTheBuyerAndTheEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        QueueStatus admitted = room.join(eventId, "giang");

        SignedJWT jwt = SignedJWT.parse(admitted.admissionToken());
        assertThat(jwt.verify(new MACVerifier(KEY.getBytes(StandardCharsets.UTF_8)))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("giang");
        assertThat(claims.getStringClaim("evt")).isEqualTo(eventId.toString());
        assertThat(claims.getIssuer()).isEqualTo("ticketrush-waiting-room");
        // JWT expiry has whole-second precision.
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(admitted.admittedUntil().truncatedTo(ChronoUnit.SECONDS));
    }

    /** FR-WR-02: the stream pushes the position until the buyer is admitted, then closes. */
    @Test
    void streamPushesThePositionUntilAdmitted() throws Exception {
        UUID eventId = UUID.randomUUID();
        room.join(eventId, "hoa");
        room.join(eventId, "khoa");

        URI stream = URI.create("http://localhost:" + port + "/api/queue/events/" + eventId + "/stream");
        assertThat(HttpClient.newHttpClient().send(HttpRequest.newBuilder(stream).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).as("no token").isEqualTo(401);

        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(stream)
                        .header("Authorization", TestJwts.bearer(TestJwts.loadTestToken(TestJwts.LOAD_TEST_SECRET, "khoa",
                                Roles.CUSTOMER)))
                        .timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofLines());
        List<String> data = response.body().filter(line -> line.startsWith("data:")).toList();

        assertThat(data.getFirst()).contains("\"state\":\"QUEUED\"", "\"position\":1");
        assertThat(data.getLast()).contains("\"state\":\"ADMITTED\"", "\"admissionToken\":\"ey");
    }
}
