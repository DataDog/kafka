package org.apache.kafka.tools;

import joptsimple.OptionSpec;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
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

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class RemoteLogMetadataAnalyzer {

    public static void main(String[] args) {
        RemoteLogMetadataAnalyzerOptions opts = new RemoteLogMetadataAnalyzerOptions(args);

        try {

            final Collection<TopicPartition> remoteLogMetadataPartition = getRemoteLogMetadataPartition(opts);

            final KafkaConsumer<byte[], byte[]> consumer = getConsumer(opts.options.valueOf(opts.bootstrapServer));
            consumer.assign(remoteLogMetadataPartition);

            try (final MessageFormatter formatter = new RemoteLogMetadataFormatter()) {
                while (true) {
                    final ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(5000));
                    if (records.isEmpty()) {
                        break;
                    }

                    for (ConsumerRecord<byte[], byte[]> record : records) {
                        formatter.writeTo(record, System.out);
                    }
                }
            }
        } catch (Exception e) {
            System.out.printf("[RemoteLogMetadataAnalyzer] %s%n",e.getMessage());
            Exit.exit(1);
        }
    }

    static Collection<TopicPartition> getRemoteLogMetadataPartition(RemoteLogMetadataAnalyzerOptions opts) throws Exception {
        final Collection<TopicListing> topics;
        try (final RemoteLogMetadataService service = new RemoteLogMetadataService(new Properties(), opts.options.valueOf(opts.bootstrapServer));) {
            topics = service.adminClient.listTopics().listings().get(120000, TimeUnit.MILLISECONDS);
        }
        final RemoteLogMetadataTopicPartitioner partitioner = new RemoteLogMetadataTopicPartitioner(20);

        final Optional<TopicListing> maybeTopic = topics.stream().filter(topic -> {return Objects.equals(topic.topicId().toString(), opts.options.valueOf(opts.topicId));}).findFirst();
        if (!maybeTopic.isPresent()) {
            System.out.printf("Unable to find topic with ID %s%n", opts.topicId);
            throw new RuntimeException(String.format("Unable to find topic with ID %s", opts.topicId));
        }

        final TopicListing topic = maybeTopic.get();
        final TopicIdPartition topicIdPartition = new TopicIdPartition(topic.topicId(), new TopicPartition(topic.name(), opts.options.valueOf(opts.partitionId)));
        return Collections.singleton(new TopicPartition("__remote_log_metadata", partitioner.metadataPartition(topicIdPartition)));
    }

    static class RemoteLogMetadataAnalyzerOptions extends CommandDefaultOptions {
        public final OptionSpec<String> bootstrapServer;
        public final OptionSpec<String> topicId;
        public final OptionSpec<Integer> partitionId;

        public RemoteLogMetadataAnalyzerOptions(String[] args) {
            super(args);
            this.bootstrapServer = parser.accepts("bootstrap-server", "Required: Kafka server to connect to")
                    .withRequiredArg()
                    .ofType(String.class);
            this.topicId = parser.accepts("topic-id", "Required: Kafka topic to retrieve metadata for")
                    .withRequiredArg()
                    .ofType(String.class);
            this.partitionId = parser.accepts("partition-id", "Required: Topic partition to retrieve metadata for")
                    .withRequiredArg()
                    .ofType(Integer.class);

            options = parser.parse(args);
        }
    }

    public static class RemoteLogMetadataService implements AutoCloseable {
        private final Admin adminClient;

        public RemoteLogMetadataService(Properties cfg, String bootstrapServer) {
            cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
            this.adminClient = Admin.create(cfg);
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
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaConsumer<>(props);
    }
}
