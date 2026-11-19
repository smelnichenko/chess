package io.schnappy.chess.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.schnappy.chess.ChessUser;
import io.schnappy.chess.ChessUserRepository;
import io.schnappy.chess.config.KafkaConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The chess-user listener against a real broker, with production's Kafka settings (application.yml) and error handler
 * (KafkaErrorHandlerConfig) - only the back-off shortened. Postgres down - past the budget of any other failure - costs
 * no user event; a failure no store explains past its retries, a value that is not JSON and an event naming no user each
 * end on user.events.DLT,
 * with headers the backup can dump, each counted once, and the partition moves on.
 */
@SpringBootTest(
        classes = {KafkaConfig.class, KafkaErrorHandlerConfig.class, UserEventConsumer.class,
                UserEventRetryKafkaTest.Deliveries.class, SimpleMeterRegistry.class},
        properties = {
                // waits of 10 ms doubling to 50 ms, 600 ms of them in all - some 14 tries (production: 1 s to 30 s,
                // 15 min). Each retry costs the embedded broker ~0.5 s of its own (a fetch after the seek), so a test
                // timed against the back-off cannot tell it from Spring's default: the outage is counted in tries
                "chess.kafka.retry.initial-interval=10ms",
                "chess.kafka.retry.max-interval=50ms",
                "chess.kafka.retry.max-elapsed=600ms"
        })
// profile "local", not "test": KafkaConfig and KafkaErrorHandlerConfig run outside the test profile only, as in
// production. It outranks CI's SPRING_PROFILES_ACTIVE, and logback's console appender shows the retries.
@ActiveProfiles("local")
// Kafka's auto-configuration, as the application gets it (KafkaConfig enables the listeners only)
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
// user.events of three partitions as in production; its .DLT made in open() with one - as the broker makes it on first
// use: a dead letter from partition 2 must still find its place there (the handler leaves the partition to Kafka)
@EmbeddedKafka(partitions = 3, topics = UserEventRetryKafkaTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
// a deserializer that fails every poll hangs the listener and fills the heap: a case fails in 90 s, the build goes on
@Timeout(90)
// the broker and the listener stop with the class: a cached context would keep them polling through the suite
@DirtiesContext
class UserEventRetryKafkaTest {

    static final String TOPIC = "user.events";
    static final String DLT = "user.events.DLT";
    private static final String CREATED = "USER_CREATED";
    private static final Duration WAIT = Duration.ofSeconds(60);
    /** More tries than Spring's default handler makes (ten) and than any failure but a store's is given (some 14). */
    private static final int FAILED_TRIES = 20;
    /** The tries a failure no store explains is given: 600 ms of waits, 10 ms doubling to 50 ms. */
    private static final int BOUNDED_TRIES = 12;

    @MockitoBean
    private ChessUserRepository chessUserRepository;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private Deliveries deliveries;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    private Producer<String, byte[]> producer;
    private Consumer<String, byte[]> dltReader;
    private final List<ConsumerRecord<String, byte[]>> deadLetters = new ArrayList<>();
    /** The dead letters counted before this test - each test that makes one waits for its own count. */
    private double countedBefore;

    /** How many times the listener was handed each user.events record (partition@offset): a retry hands it again. */
    @TestComponent
    static class Deliveries implements RecordInterceptor<Object, Object> {
        private final Map<String, AtomicInteger> byRecord = new ConcurrentHashMap<>();

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> rec,
                                                        Consumer<Object, Object> consumer) {
            byRecord.computeIfAbsent(rec.partition() + "@" + rec.offset(), k -> new AtomicInteger()).incrementAndGet();
            return rec;
        }

        int of(String at) {
            AtomicInteger n = byRecord.get(at);
            return n == null ? 0 : n.get();
        }
    }

    @BeforeEach
    void open() {
        var adminConfig = Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        try (Admin admin = Admin.create(adminConfig)) {
            // a broker just started (KRaft) may time out the create, and answer UnknownTopicOrPartition for a topic just
            // created: both retried till it has the topic. The premise of the partition-2 case: the broker's own
            // default (num.partitions) is three
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
                try {
                    admin.createTopics(List.of(new NewTopic(DLT, 1, (short) 1))).all().get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw e;
                    }
                }
                assertThat(admin.describeTopics(List.of(DLT)).allTopicNames().get(2, TimeUnit.SECONDS).get(DLT).partitions())
                        .as("the .DLT of one partition").hasSize(1);
            });
        }
        countedBefore = deadLettersCounted();
        // a save answers with what it saved
        when(chessUserRepository.save(any(ChessUser.class))).thenAnswer(inv -> inv.getArgument(0));
        producer = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()),
                new StringSerializer(), new ByteArraySerializer());
        dltReader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
        dltReader.subscribe(List.of(DLT));
    }

    @AfterEach
    void close() {
        producer.close();
        dltReader.close();
    }

    @Test
    void storeDownForAWhile_theUserStoredOnceItIsBack() throws Exception {
        UUID uuid = UUID.randomUUID();
        AtomicInteger tries = new AtomicInteger();
        AtomicInteger stored = new AtomicInteger();
        doAnswer(inv -> {
            if (tries.incrementAndGet() <= FAILED_TRIES) {
                throw new DataAccessResourceFailureException("Postgres restarting");
            }
            stored.incrementAndGet();
            return inv.getArgument(0);
        }).when(chessUserRepository).save(user(uuid));

        String at = send(CREATED, created(uuid, "restart@example.com"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(stored).hasValue(1));
        assertThat(tries).hasValue(FAILED_TRIES + 1);
        assertThat(deliveries.of(at)).as("stored on the try after the failed ones").isEqualTo(FAILED_TRIES + 1);
    }

    @Test
    void aFailureNoStoreExplains_pastItsRetries_theRecordKeptOnTheDlt_andThePartitionMovesOn() throws Exception {
        // not the store away (that waits for it, however long): a failure no wait cures, given its 15 min - here 600 ms
        UUID uuid = UUID.randomUUID();
        doThrow(new IllegalStateException("a bug in the listener")).when(chessUserRepository).save(user(uuid));

        String at = send(CREATED, created(uuid, "gone@example.com"));

        ConsumerRecord<String, byte[]> dead = deadLetter(uuid.toString());
        assertThat(dead.key()).isEqualTo(CREATED);
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo("chess-user");
        assertThat(failure(dead)).contains(IllegalStateException.class.getName());
        assertThat(header(dead, KafkaErrorHandlerConfig.EXCEPTION_MESSAGE)).isEqualTo("a bug in the listener");
        assertThat(deliveries.of(at)).as("retried past Spring's default ten tries before it was given up on")
                .isGreaterThan(BOUNDED_TRIES);
        backupSafe(dead);
        countedOnce();

        UUID next = UUID.randomUUID();
        send(CREATED, created(next, "next@example.com"));
        verify(chessUserRepository, timeout(WAIT.toMillis())).save(user(next));
    }

    @Test
    void aRowBreakingAConstraint_retriedItsBudget_thenKeptOnTheDlt() throws Exception {
        // retryable in chess (an overlapping insert is found on the retry, the next case): a constraint the retry does
        // not cure gets the bounded budget, then the DLT. Postgres's own message spans lines ("Detail:" after it): one
        // line on the .DLT - Spring's own message header (multi-line) excluded, the backup's text
        UUID uuid = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint on chess_users\n"
                + "  Detail:\tKey (uuid)=(" + uuid + ") already exists."))
                .when(chessUserRepository).save(user(uuid));

        String at = send(CREATED, created(uuid, "taken@example.com"));

        ConsumerRecord<String, byte[]> dead = deadLetter(uuid.toString());
        assertThat(failure(dead)).contains(DataIntegrityViolationException.class.getName());
        assertThat(header(dead, KafkaErrorHandlerConfig.EXCEPTION_MESSAGE)).isEqualTo(
                "duplicate key value violates unique constraint on chess_users Detail: Key (uuid)=(" + uuid
                        + ") already exists.");
        assertThat(deliveries.of(at)).as("retried its budget, not dead-lettered at once").isGreaterThan(BOUNDED_TRIES);
        backupSafe(dead);
        countedOnce();
    }

    @Test
    void aDuplicateInsert_theRowAnOverlappingConsumerCommitted_foundOnTheRetry() throws Exception {
        // two pods: the other committed this user between this one's find and its insert. The retry finds that row
        // and updates it - nothing on the DLT
        UUID uuid = UUID.randomUUID();
        var committed = new ChessUser();
        committed.setUuid(uuid);
        committed.setEmail("first@example.com");
        when(chessUserRepository.findByUuid(uuid)).thenReturn(Optional.empty(), Optional.of(committed));
        AtomicInteger saves = new AtomicInteger();
        doAnswer(inv -> {
            if (saves.incrementAndGet() == 1) {
                throw new DataIntegrityViolationException("duplicate key value violates unique constraint on chess_users");
            }
            return inv.getArgument(0);
        }).when(chessUserRepository).save(user(uuid));

        String at = send(CREATED, created(uuid, "second@example.com"));

        verify(chessUserRepository, timeout(WAIT.toMillis())).save(argThat(u -> u == committed));
        assertThat(committed.getEmail()).isEqualTo("second@example.com");
        assertThat(deliveries.of(at)).as("the duplicate, then the update").isEqualTo(2);
        assertThat(deadLettersCounted()).as("nothing on the DLT").isEqualTo(countedBefore);
    }

    @Test
    void anEventNamingNoUser_keptOnTheDltAtOnce() throws Exception {
        String marker = "no-uuid-" + UUID.randomUUID();
        String at = send(CREATED, """
                {"type":"USER_CREATED","email":"%s@example.com","enabled":true}""".formatted(marker));

        ConsumerRecord<String, byte[]> dead = deadLetter(marker);
        assertThat(failure(dead)).contains(InvalidUserEventException.class.getName());
        assertThat(deliveries.of(at)).as("never retried").isEqualTo(1);
        verify(chessUserRepository, never()).save(argThat(u -> u != null && u.getEmail() != null
                && u.getEmail().startsWith(marker)));
        backupSafe(dead);
        countedOnce();
    }

    @Test
    void wiredAsInProduction_theHandlerThere_noTestProfile() {
        // CI runs with SPRING_PROFILES_ACTIVE=test, and KafkaConfig and the handler run outside the test profile
        // only: this class's own profile must win, or every case below waits out its timeout for no listener
        assertThat(environment.getActiveProfiles()).contains("local").doesNotContain("test", "ci");
        assertThat(context.containsBean("kafkaErrorHandler")).isTrue();
        // application.yml's ErrorHandlingDeserializer and its JsonDeserializer delegate, as production reads them
        assertThat(consumerFactory.getConfigurationProperties())
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class)
                .containsKey(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS);
        // both counters at 0 from the start, before any dead letter: the alert reads their increase
        assertThat(meters.find(KafkaErrorHandlerConfig.DEAD_LETTERS).tag("topic", TOPIC).counter()).isNotNull();
        assertThat(meters.find(KafkaErrorHandlerConfig.DEAD_LETTERS_REFUSED).tag("topic", TOPIC).counter()).isNotNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aValueThatIsNotJson_keptOnTheDltAsItCame_andThePartitionMovesOn(CapturedOutput output) throws Exception {
        // on partition 2 - its .DLT has one: the dead letter goes where Kafka puts it, not to a partition 2 it lacks
        String poison = "not json " + UUID.randomUUID();
        send(2, CREATED, poison);
        UUID next = UUID.randomUUID();
        send(2, CREATED, created(next, "after-poison@example.com"));

        ConsumerRecord<String, byte[]> dead = deadLetter(poison);
        assertThat(dead.value()).as("its original bytes, not JSON of them").isEqualTo(poison.getBytes(UTF_8));
        assertThat(failure(dead)).contains(DeserializationException.class.getName());
        assertThat(header(dead, KafkaErrorHandlerConfig.ORIGINAL_PARTITION)).isEqualTo("2");
        // no count of its deliveries: with no transaction manager the container calls the interceptor after its
        // deserialization check, so a value that never deserialized is never counted
        backupSafe(dead);
        verify(chessUserRepository, timeout(WAIT.toMillis())).save(user(next));
        countedOnce();
        // no partition asked for: Spring's same-partition default would name user.events.DLT-2, which its recoverer
        // checks and replaces with Kafka's choice - logged per dead letter
        assertThat(output.getAll()).doesNotContain("non-existent partition");
    }

    /**
     * A record as admin sends it: keyed by its type; the type headers Spring's JsonSerializer writes for a HashMap (a
     * container: its own class, its content's and its key's - JsonDeserializer refuses a map without the last two).
     */
    private String send(String key, String value) throws Exception {
        return send(null, key, value);
    }

    /** The record sent to that partition (null: by its key), as partition@offset. */
    private String send(Integer partition, String key, String value) throws Exception {
        var rec = new ProducerRecord<>(TOPIC, partition, key, value.getBytes(UTF_8));
        rec.headers().add("__TypeId__", "java.util.HashMap".getBytes(UTF_8));
        rec.headers().add("__ContentTypeId__", "java.lang.Object".getBytes(UTF_8));
        rec.headers().add("__KeyTypeId__", "java.lang.Object".getBytes(UTF_8));
        RecordMetadata sent = producer.send(rec).get(10, TimeUnit.SECONDS);
        return sent.partition() + "@" + sent.offset();
    }

    private static ChessUser user(UUID uuid) {
        return argThat(u -> u != null && uuid.equals(u.getUuid()));
    }

    private static String created(UUID uuid, String email) {
        return """
                {"type":"USER_CREATED","uuid":"%s","email":"%s","enabled":true}""".formatted(uuid, email);
    }

    /** The .DLT record whose value holds the marker. */
    private ConsumerRecord<String, byte[]> deadLetter(String marker) {
        await().atMost(WAIT).pollInSameThread().until(() -> {
            dltReader.poll(Duration.ofMillis(200)).forEach(deadLetters::add);
            return deadLetters.stream().anyMatch(r -> holds(r, marker));
        });
        return deadLetters.stream().filter(r -> holds(r, marker)).findFirst().orElseThrow();
    }

    /**
     * Every header of the dead letter as the nightly backup dumps it - text: valid UTF-8, one line, no tab and no 0x1E
     * (its separators). Spring's own partition, offset and timestamp headers are binary and its stack trace spans
     * lines: one such record made the dump refuse every topic.
     */
    private static void backupSafe(ConsumerRecord<?, ?> rec) {
        for (Header h : rec.headers()) {
            String text;
            try {
                text = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(h.value())).toString();
            } catch (CharacterCodingException e) {
                throw new AssertionError("header " + h.key() + " is not UTF-8 text", e);
            }
            assertThat(text).as("header %s", h.key()).doesNotContainPattern("\\p{Cntrl}");
        }
    }

    /** This test's dead letter counted, once: the count comes just after the .DLT took it. */
    private void countedOnce() {
        await().atMost(WAIT).untilAsserted(() -> assertThat(deadLettersCounted()).isEqualTo(countedBefore + 1));
    }

    /** kafka_dead_letters_total{topic="user.events"}, what the KafkaDeadLetters alert reads. */
    private double deadLettersCounted() {
        Counter counter = meters.find(KafkaErrorHandlerConfig.DEAD_LETTERS).tag("topic", TOPIC).counter();
        return counter == null ? 0 : counter.count();
    }

    private static boolean holds(ConsumerRecord<String, byte[]> rec, String marker) {
        return rec.value() != null && new String(rec.value(), UTF_8).contains(marker);
    }

    /** The failure's exception and its cause, as the .DLT record's headers name them. */
    private static String failure(ConsumerRecord<?, ?> rec) {
        return header(rec, KafkaHeaders.DLT_EXCEPTION_FQCN) + " " + header(rec, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private static String header(ConsumerRecord<?, ?> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), UTF_8);
    }
}
