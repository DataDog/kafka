package org.apache.kafka.server.metrics.dd;


public class DatadogMetrics {

  private static int RESERVOIR_SIZE = 2056;
  private static UniformReservoir produceLatencyReservoir = new UniformReservoir(RESERVOIR_SIZE);

  public static UniformReservoir getInstance() {
    return produceLatencyReservoir;
  }

}
