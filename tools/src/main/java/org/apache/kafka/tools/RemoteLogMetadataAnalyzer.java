package org.apache.kafka.tools;

import joptsimple.OptionSpec;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.MessageFormatter;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.Exit;
import org.apache.kafka.server.log.remote.metadata.storage.RemoteLogMetadataTopicPartitioner;
import org.apache.kafka.server.log.remote.metadata.storage.serialization.RemoteLogMetadataSerde.RemoteLogMetadataFormatter;
import org.apache.kafka.server.util.CommandDefaultOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteLogMetadataAnalyzer {

    public static void main(String[] args) {
        RemoteLogMetadataAnalyzerOptions opts = new RemoteLogMetadataAnalyzerOptions(args);
        try {
            final TopicPartition remoteLogMetadataPartition = getRemoteLogMetadataPartition(opts);
            final KafkaConsumer<byte[], byte[]> consumer = getConsumer(opts.options.valueOf(opts.bootstrapServer));
            consumer.assign(Collections.singletonList(remoteLogMetadataPartition));
            final ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(opts.getPollDuration()));

            try (final MessageFormatter formatter = new RemoteLogMetadataFormatter()) {
                if (opts.getSegmentId().isPresent()) {
                    formatRecordsBySegmentId(records, formatter, opts.getSegmentId().get());
                } else {
                    formatRecords(records, formatter);
                }
            }
        } catch (Exception e) {
            System.err.printf("[RemoteLogMetadataAnalyzer] %s%n", e);
            Exit.exit(1);
        }
    }

    private static void formatRecords(ConsumerRecords<byte[], byte[]> records, MessageFormatter formatter) {
        for (ConsumerRecord<byte[], byte[]> record : records) {
            formatter.writeTo(record, System.out);
        }
    }

    private static void formatRecordsBySegmentId(ConsumerRecords<byte[], byte[]> records, MessageFormatter formatter, String segmentId) throws IOException {
        try (
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                final PrintStream stream = new PrintStream(outputStream, true, StandardCharsets.UTF_8.name())
        ) {
            for (ConsumerRecord<byte[], byte[]> record : records) {
                // parse the data until we find the segment
                formatter.writeTo(record, stream);
                if (outputStream.toString().contains(segmentId)) {
                    formatter.writeTo(record, System.out);
                    stream.flush();
                }
                outputStream.reset();
            }
        }
    }

    static TopicPartition getRemoteLogMetadataPartition(RemoteLogMetadataAnalyzerOptions opts) throws Exception {
        final Collection<TopicListing> topics;
        final TopicDescription remoteLogMetadataTopic;
        try (final RemoteLogMetadataService service = new RemoteLogMetadataService(new Properties(), opts.getBootstrapServer())) {
            topics = service.listTopics();
            remoteLogMetadataTopic = service.getRemoteLogMetadataTopic(opts.getRemoteLogMetadataTopicName());
        }

        final RemoteLogMetadataTopicPartitioner partitioner = new RemoteLogMetadataTopicPartitioner(remoteLogMetadataTopic.partitions().size());

        final Optional<TopicListing> maybeTopic = topics.stream().filter(topic -> Objects.equals(topic.name(), opts.getTopicName())).findFirst();
        if (!maybeTopic.isPresent()) {
            System.err.printf("[RemoteLogMetadataAnalyzer] Unable to find topic with name %s%n", opts.getTopicName());
            throw new NoSuchElementException(String.format("Unable to find topic with name %s", opts.getTopicName()));
        }

        final TopicListing topic = maybeTopic.get();
        final TopicIdPartition topicIdPartition = new TopicIdPartition(topic.topicId(), new TopicPartition(topic.name(), opts.getPartitionId()));
        return new TopicPartition(opts.getRemoteLogMetadataTopicName(), partitioner.metadataPartition(topicIdPartition));
    }

    static class RemoteLogMetadataAnalyzerOptions extends CommandDefaultOptions {
        private final OptionSpec<String> bootstrapServer;
        private final OptionSpec<String> topic;
        private final OptionSpec<Integer> partition;
        private final OptionSpec<String> metadataTopic;
        private final OptionSpec<String> segmentId;

        private final OptionSpec<Long> pollDuration;

        public RemoteLogMetadataAnalyzerOptions(String[] args) {
            super(args);
            this.bootstrapServer = parser.accepts("bootstrap-server", "Required: Kafka server to connect to")
                    .withRequiredArg()
                    .ofType(String.class);
            this.topic = parser.accepts("topic", "Required: Kafka topic name to retrieve metadata for")
                    .withRequiredArg()
                    .ofType(String.class);
            this.partition = parser.accepts("partition", "Required: Topic partition to retrieve metadata for")
                    .withRequiredArg()
                    .ofType(Integer.class);
            this.metadataTopic = parser.accepts("metadata-topic", "Optional: Topic name containing the remote log metadata, default: __remote_log_metadata")
                    .withOptionalArg()
                    .ofType(String.class)
                    .defaultsTo("__remote_log_metadata");
            this.segmentId = parser.accepts("segment-id", "Optional: Segment ID to retrieve metadata for")
                    .withOptionalArg()
                    .ofType(String.class);
            this.pollDuration = parser.accepts("poll-duration", "Optional: Time in milliseconds to poll remote log metadata for, default: 5000")
                    .withOptionalArg()
                    .ofType(Long.class)
                    .defaultsTo(5000L);

            options = parser.parse(args);
        }

        public String getBootstrapServer() {
            return options.valueOf(bootstrapServer);
        }

        public String getTopicName() {
            return options.valueOf(topic);
        }

        public Integer getPartitionId() {
            return options.valueOf(partition);
        }

        public Optional<String> getSegmentId() {
            return Optional.ofNullable(options.valueOf(segmentId));
        }

        public String getRemoteLogMetadataTopicName() {
            return options.valueOf(metadataTopic);
        }

        public Long getPollDuration() {
            return options.valueOf(pollDuration);
        }
    }

    public static class RemoteLogMetadataService implements AutoCloseable {
        private final Admin adminClient;
        private static final int ADMIN_TIMEOUT_MS = 120000;

        public RemoteLogMetadataService(Properties cfg, String bootstrapServer) {
            cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
            this.adminClient = Admin.create(cfg);
        }

        public Collection<TopicListing> listTopics() throws ExecutionException, InterruptedException, TimeoutException {
            return adminClient.listTopics().listings().get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public TopicDescription getRemoteLogMetadataTopic(String remoteLogMetadataTopicName) throws ExecutionException, InterruptedException, TimeoutException {
            return adminClient
                    .describeTopics(Collections.singletonList(remoteLogMetadataTopicName))
                    .allTopicNames()
                    .get(ADMIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .get(remoteLogMetadataTopicName);
        }

        @Override
        public void close() throws Exception {
            this.adminClient.close();
        }
    }

    private static KafkaConsumer<byte[], byte[]> getConsumer(String bootstrapServer) {
        final Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaConsumer<>(props);
    }
}
