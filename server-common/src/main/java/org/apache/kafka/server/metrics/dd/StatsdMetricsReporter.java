package org.apache.kafka.server.metrics.dd;

import com.timgroup.statsd.NonBlockingStatsDClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StatsdMetricsReporter implements MetricsReporter {

  private static String METRIC_NAME = "dd.kafka.produce.time";

  private static final Logger log = LoggerFactory.getLogger(StatsdMetricsReporter.class);

  private final ScheduledExecutorService executor;

  private static final Duration PERIOD = Duration.ofSeconds(5);

  public StatsdMetricsReporter() {
    executor = Executors.newSingleThreadScheduledExecutor();
  }

  @Override
  public void configure(Map<String, ?> configs) {
    Runnable emitter = new Runnable() {
      @Override
      public void run() {
        Snapshot values = DatadogMetrics.getInstance().getSnapshot();
        // Could be a race condition here and some measurements could be lost
        // but at the usual rate of produce requests this is okay
        DatadogMetrics.getInstance().clear();
        NonBlockingStatsDClient statsd = StatsDClient.getInstance();
        for(long v: values.getValues()){
         statsd.recordDistributionValue(METRIC_NAME, v);
        }
      }
    };
    executor.scheduleAtFixedRate(emitter, PERIOD.getSeconds(), PERIOD.getSeconds(), TimeUnit.SECONDS);
    log.info("Configured DogstatsdReporter to report every {} seconds", PERIOD);
  }

  @Override
  public void init(List<KafkaMetric> metrics) {
  }

  @Override
  public void metricChange(KafkaMetric metric) {
  }

  @Override
  public void metricRemoval(KafkaMetric metric) {
  }

  @Override
  public void close() {

  }
}
