package com.ticketrush.event;

import static com.ticketrush.security.TestJwts.customer;
import static com.ticketrush.security.TestJwts.organizer;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.ticketrush.TestcontainersConfiguration;
import com.ticketrush.common.messaging.MessageHeaders;
import com.ticketrush.common.messaging.Topics;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventApiIntegrationTest {

    private static final String ORGANIZER = "organizer-1";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    KafkaConnectionDetails kafka;

    @Test
    void draftIsHiddenUntilPublishedAndThenLocked() {
        String city = "Da Lat " + UUID.randomUUID();
        String id = createEvent(city);

        assertThat(searchByCity(city)).bodyJson().extractingPath("$.totalItems").isEqualTo(0);

        assertThat(mvc.post().uri("/api/events/{id}/publish", id).with(organizer(ORGANIZER)))
                .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("PUBLISHED");

        assertThat(searchByCity(city)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.items[0].id", value -> value.assertThat().isEqualTo(id))
                .hasPathSatisfying("$.items[0].totalSeats", value -> value.assertThat().isEqualTo(10))
                .hasPathSatisfying("$.items[0].minPriceVnd", value -> value.assertThat().isEqualTo(800000));

        assertThat(mvc.put().uri("/api/events/{id}", id).with(organizer(ORGANIZER))
                .contentType(MediaType.APPLICATION_JSON).content(eventJson(city, "VIP")))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.title").isEqualTo("Conflict");
    }

    @Test
    void publishingAnnouncesTheEventOnKafkaExactlyOnce() {
        String id = createEvent("Hue");

        mvc.post().uri("/api/events/{id}/publish", id).with(organizer(ORGANIZER)).exchange();
        mvc.post().uri("/api/events/{id}/publish", id).with(organizer(ORGANIZER)).exchange();

        List<ConsumerRecord<String, String>> published = readEventTopicFor(id, Duration.ofSeconds(10));
        assertThat(published).hasSize(1);
        ConsumerRecord<String, String> record = published.getFirst();
        assertThat(header(record, MessageHeaders.MESSAGE_TYPE)).isEqualTo("EventPublished");
        assertThat((List<?>) JsonPath.read(record.value(), "$.sections")).hasSize(2);
        assertThat((Integer) JsonPath.read(record.value(), "$.version")).isEqualTo(1);
    }

    @Test
    void draftsCanBeEditedOnlyByTheirOrganizer() {
        String id = createEvent("Hanoi");

        assertThat(mvc.put().uri("/api/events/{id}", id).with(organizer(ORGANIZER))
                .contentType(MediaType.APPLICATION_JSON).content(eventJson("Hanoi", "VVIP")))
                .hasStatusOk().bodyJson().extractingPath("$.sections[0].code").isEqualTo("VVIP");

        assertThat(mvc.post().uri("/api/events/{id}/publish", id).with(organizer("someone-else")))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/events/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsInvalidEventsWithProblemDetails() {
        Instant startsAt = Instant.now().plus(30, ChronoUnit.DAYS);
        String salesAfterStart = """
                {"name":"Late sale","venue":"Venue","city":"HCMC","startsAt":"%s","salesOpenAt":"%s",
                 "sections":[{"code":"GA","name":"Standard","rows":1,"seatsPerRow":1,"priceVnd":1}]}
                """.formatted(startsAt, startsAt.plus(1, ChronoUnit.DAYS));
        assertThat(mvc.post().uri("/api/events").with(organizer(ORGANIZER))
                .contentType(MediaType.APPLICATION_JSON).content(salesAfterStart))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String badSectionCode = eventJson("HCMC", "vip-lower");
        assertThat(mvc.post().uri("/api/events").with(organizer(ORGANIZER))
                .contentType(MediaType.APPLICATION_JSON).content(badSectionCode))
                .hasStatus(HttpStatus.BAD_REQUEST);

    }

    @Test
    void onlyOrganizersChangeTheCatalogue() {
        assertThat(mvc.post().uri("/api/events")
                .contentType(MediaType.APPLICATION_JSON).content(eventJson("HCMC", "VIP")))
                .as("no token").hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/events").with(customer("an"))
                .contentType(MediaType.APPLICATION_JSON).content(eventJson("HCMC", "VIP")))
                .as("customer").hasStatus(HttpStatus.FORBIDDEN).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(mvc.get().uri("/api/events").param("city", "HCMC")).as("browsing is public").hasStatusOk();
    }

    private String createEvent(String city) {
        MvcTestResult result = mvc.post().uri("/api/events").with(organizer(ORGANIZER))
                .contentType(MediaType.APPLICATION_JSON).content(eventJson(city, "VIP")).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.status").isEqualTo("DRAFT");
        return JsonPath.read(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");
    }

    private MvcTestResult searchByCity(String city) {
        return mvc.get().uri("/api/events").param("city", city).exchange();
    }

    /** Two sections: VIP 1x4 at 3,000,000 VND and GA 2x3 at 800,000 VND, 10 seats in total. */
    private static String eventJson(String city, String vipCode) {
        Instant startsAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        return """
                {"name":"Rock Night","venue":"Sân vận động Mỹ Đình","city":"%s","startsAt":"%s","salesOpenAt":"%s",
                 "sections":[
                   {"code":"%s","name":"VIP","rows":1,"seatsPerRow":4,"priceVnd":3000000},
                   {"code":"GA","name":"Standard","rows":2,"seatsPerRow":3,"priceVnd":800000}]}
                """.formatted(city, startsAt, startsAt.minus(7, ChronoUnit.DAYS), vipCode);
    }

    private List<ConsumerRecord<String, String>> readEventTopicFor(String eventId, Duration window) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.EVENT_EVENTS));
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (eventId.equals(record.key())) {
                        matching.add(record);
                    }
                });
            }
        }
        return matching;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
