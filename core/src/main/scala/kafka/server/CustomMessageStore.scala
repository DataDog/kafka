package kafka.server
import kafka.Kafka.info
import org.apache.kafka.common.{TopicIdPartition, TopicPartition}
import org.apache.kafka.common.record.{MemoryRecords, RecordValidationStats}
import org.apache.kafka.common.requests.{FetchRequest, ProduceResponse}
import org.apache.kafka.common.utils.Time
import org.apache.kafka.storage.internals.log.{AppendOrigin, FetchParams, FetchPartitionData, LogOffsetMetadata}

import java.util.concurrent.locks.Lock

class CustomMessageStore(replicaManager: ReplicaManager) extends IMessageStore {
  val time = Time.SYSTEM

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
  override def appendRecords(
      timeout: Long,
      requiredAcks: Short,
      internalTopicsAllowed: Boolean,
      origin: AppendOrigin,
      entriesPerPartition: collection.Map[TopicPartition, MemoryRecords],
      responseCallback: collection.Map[TopicPartition, ProduceResponse.PartitionResponse] => Unit,
      delayedProduceLock: Option[Lock],
      recordValidationStatsCallback: collection.Map[TopicPartition, RecordValidationStats] => Unit,
      requestLocal: RequestLocal,
      transactionalId: String,
      actionQueue: ActionQueue): Unit = {
    if (!origin.equals(AppendOrigin.CLIENT)) {
      throw new NotImplementedError("only support dumb produce requests, none of that transactional bs")
    }
    info(s"received produce request with payload $entriesPerPartition")
    //entriesPerPartition.foreach { entry =>
    //  // todo: validate still leader for this partition
    //  val updated: Seq[MemoryRecords] = inMemoryState.getOrElse(entry._1, List()) :+ entry._2
    //  inMemoryState.update(entry._1, updated)
    //}
    responseCallback(Map())
  }

  /**
   * Fetch messages from a replica, and wait until enough data can be fetched and return;
   * the callback function will be triggered either when timeout or required fetch info is satisfied.
   * Consumers may fetch from any replica, but followers can only fetch from the leader.
   */
  override def fetchMessages(
      params: FetchParams,
      fetchInfos: collection.Seq[(TopicIdPartition, FetchRequest.PartitionData)],
      quota: ReplicaQuota,
      responseCallback: collection.Seq[(TopicIdPartition, FetchPartitionData)] => Unit): Unit = {
    info(s"received fetch request with payload $fetchInfos, not responding but will update follower state to say it's in sync")
    if (params.isFromFollower) {
      fetchInfos.foreach { case (tp, fetchInfo) =>
        val partition = replicaManager.getPartitionOrException(tp.topicPartition)
        val replica = partition.followerReplicaOrThrow(params.replicaId, fetchInfo)
        partition.updateFollowerFetchState(
          replica,
          followerFetchOffsetMetadata = new LogOffsetMetadata(fetchInfo.fetchOffset),
          followerStartOffset = fetchInfo.logStartOffset,
          followerFetchTimeMs = time.milliseconds(),
          leaderEndOffset = fetchInfo.fetchOffset,
          params.replicaEpoch
        )
      }
    }
  }
}
