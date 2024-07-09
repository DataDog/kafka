package kafka.server

import org.apache.kafka.common.{TopicIdPartition, TopicPartition}
import org.apache.kafka.common.record.{MemoryRecords, RecordValidationStats}
import org.apache.kafka.common.requests.FetchRequest.PartitionData
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.storage.internals.log.{AppendOrigin, FetchParams, FetchPartitionData}

import java.util.concurrent.locks.Lock
import scala.collection.{Map, Seq}

trait IMessageStore {
  /**
   * Append messages to leader replicas of the partition, and wait for them to be replicated to other replicas;
   * the callback function will be triggered either when timeout or the required acks are satisfied;
   * if the callback function itself is already synchronized on some object then pass this object to avoid deadlock.
   *
   * Noted that all pending delayed check operations are stored in a queue. All callers to ReplicaManager.appendRecords()
   * are expected to call ActionQueue.tryCompleteActions for all affected partitions, without holding any conflicting
   * locks.
   *
   * @param timeout                       maximum time we will wait to append before returning
   * @param requiredAcks                  number of replicas who must acknowledge the append before sending the response
   * @param internalTopicsAllowed         boolean indicating whether internal topics can be appended to
   * @param origin                        source of the append request (ie, client, replication, coordinator)
   * @param entriesPerPartition           the records per partition to be appended
   * @param responseCallback              callback for sending the response
   * @param delayedProduceLock            lock for the delayed actions
   * @param recordValidationStatsCallback callback for updating stats on record conversions
   * @param requestLocal                  container for the stateful instances scoped to this request
   * @param transactionalId               transactional ID if the request is from a producer and the producer is transactional
   * @param actionQueue                   the action queue to use. ReplicaManager#defaultActionQueue is used by default.
   */
  def appendRecords(timeout: Long,
                    requiredAcks: Short,
                    internalTopicsAllowed: Boolean,
                    origin: AppendOrigin,
                    entriesPerPartition: Map[TopicPartition, MemoryRecords],
                    responseCallback: Map[TopicPartition, PartitionResponse] => Unit,
                    delayedProduceLock: Option[Lock] = None,
                    recordValidationStatsCallback: Map[TopicPartition, RecordValidationStats] => Unit = _ => (),
                    requestLocal: RequestLocal = RequestLocal.NoCaching,
                    transactionalId: String = null,
                    actionQueue: ActionQueue = null): Unit

  /**
   * Fetch messages from a replica, and wait until enough data can be fetched and return;
   * the callback function will be triggered either when timeout or required fetch info is satisfied.
   * Consumers may fetch from any replica, but followers can only fetch from the leader.
   */
  def fetchMessages(params: FetchParams,
                    fetchInfos: Seq[(TopicIdPartition, PartitionData)],
                    quota: ReplicaQuota,
                    responseCallback: Seq[(TopicIdPartition, FetchPartitionData)] => Unit): Unit

}
