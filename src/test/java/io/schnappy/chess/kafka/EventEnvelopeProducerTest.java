package io.schnappy.chess.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// KafkaTemplate.send is overloaded; matching on the raw ProducerRecord type
// disambiguates the overload at the cost of an unchecked-conversion warning.
@SuppressWarnings("unchecked")
class EventEnvelopeProducerTest {

    private static final String CHANNEL = "chess:game:abc";
    private static final String KEY = "abc";

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, Object>> recordCaptor;

    private EventEnvelopeProducer producer;
    private EventEnvelope envelope;

    @BeforeEach
    void setUp() {
        producer = new EventEnvelopeProducer(kafkaTemplate);
        envelope = EventEnvelope.of("move.made", "game:abc", "actor", "payload");
    }

    @Test
    void publish_buildsRecordWithTopicKeyAndChannelHeader() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        producer.publish(CHANNEL, KEY, envelope);

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, Object> sent = recordCaptor.getValue();
        assertThat(sent.topic()).isEqualTo("events.chess.moves");
        assertThat(sent.key()).isEqualTo(KEY);
        assertThat(sent.value()).isEqualTo(envelope);
        var header = sent.headers().lastHeader("x-centrifugo-channels");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(CHANNEL);
    }

    @Test
    void publish_successfulSend_completesWithoutError() {
        // The success path of the whenComplete callback (ex == null). The result
        // value is never read, so a null-completed future drives the branch.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        producer.publish(CHANNEL, KEY, envelope);

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void publish_failedSend_logsErrorBranch() {
        // The error path: an exceptionally-completed future drives the ex != null
        // branch so the failure is logged rather than thrown.
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        producer.publish(CHANNEL, KEY, envelope);

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }
}
