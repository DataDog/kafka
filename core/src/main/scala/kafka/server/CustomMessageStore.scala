package kafka.server
import kafka.Kafka.info
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.{TopicIdPartition, TopicPartition}
import org.apache.kafka.common.record.{MemoryRecords, RecordValidationStats}
import org.apache.kafka.common.requests.{FetchRequest, ProduceResponse}
import org.apache.kafka.common.utils.Time
import org.apache.kafka.storage.internals.log.{AppendOrigin, FetchParams, FetchPartitionData}
import org.apache.log4j.helpers.LogLog.warn

import java.util.concurrent.locks.Lock
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class CustomMessageStore(replicaManager: ReplicaManager) extends IMessageStore {
  // something simple but slow to start with
  val lock: Object = new Object()
  @volatile var inMemoryState: mutable.Map[TopicPartition, Seq[MemoryRecords]] = mutable.Map()

  private val customMessageStoredDelayedFetchPurgatory = DelayedOperationPurgatory[CustomMessageStoreDelayedFetch](
    purgatoryName = "CustomMessageStoreFetch",
    brokerId = replicaManager.config.brokerId,
    purgeInterval = replicaManager.config.fetchPurgatoryPurgeIntervalRequests
  )

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
      throw new NotImplementedError("only support basic produce requests, nothing related to transactions")
    }
    info(s"received produce request with payload $entriesPerPartition")
    lock.synchronized {
      val produceResponse: mutable.Map[TopicPartition, ProduceResponse.PartitionResponse] = mutable.Map()
      entriesPerPartition.foreach { entry =>
        // todo: validate still leader for this partition

        // todo: size validation, crc validation, etc

        // todo: offset mgmt currently assume single message (hence single compression batch) per produce request
        val initialRecords: Seq[MemoryRecords] = inMemoryState.getOrElse(entry._1, List())
        val initialLogEndOffset = initialRecords.size - 1

        val newRecords = entry._2
        val newLogEndOffset = initialLogEndOffset + 1

        newRecords.batches().asScala.last.setLastOffset(initialLogEndOffset+1)
        inMemoryState.update(entry._1, initialRecords :+ newRecords)

        produceResponse.put(entry._1, new ProduceResponse.PartitionResponse(
          Errors.NONE,
          initialLogEndOffset,
          newLogEndOffset,
          Time.SYSTEM.milliseconds(),
          0L, // log start offset, always 0 since no retention enforcement yet
          Seq().asJava, // assume no error records
          "" // hence no error message
        ))
      }
    }
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
    if (params.isFromFollower) {
      warn(s"received fetch request with params $params and payload $fetchInfos, unexpected since this is from a follower")
      throw new NotImplementedError("custom message store only supports RF=1 topics, there should be no internal replication")
    }
    val delayedFetch = new CustomMessageStoreDelayedFetch(
      params = params,
      fetchInfos = fetchInfos,
      responseCallback = responseCallback,
      storeState = inMemoryState,
      storeStateLock = lock,
    )
    val delayedFetchKeys = fetchInfos.map { case (tp, _) => TopicPartitionOperationKey(tp) }
    customMessageStoredDelayedFetchPurgatory.tryCompleteElseWatch(delayedFetch, delayedFetchKeys)
  }
}
