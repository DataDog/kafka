package org.apache.kafka.server.metrics.dd;

/**
 * A statistically representative reservoir of a data stream.
 * Copied from https://github.com/dropwizard/metrics
 */
public interface Reservoir {
  /**
   * Returns the number of values recorded.
   *
   * @return the number of values recorded
   */
  int size();

  /**
   * Adds a new recorded value to the reservoir.
   *
   * @param value a new recorded value
   */
  void update(long value);


  void update(double value);


  // Empties the reservoir
  void clear();

  /**
   * Returns a snapshot of the reservoir's values.
   *
   * @return a snapshot of the reservoir's values
   */
  Snapshot getSnapshot();
}
