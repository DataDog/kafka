package kafka.server

import org.apache.kafka.common.{TopicIdPartition, TopicPartition}
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.requests.FetchRequest
import org.apache.kafka.storage.internals.log.{FetchParams, FetchPartitionData}

import java.util.{Optional, OptionalInt, OptionalLong}
import scala.collection.mutable

class CustomMessageStoreDelayedFetch(
    params: FetchParams,
    fetchInfos: collection.Seq[(TopicIdPartition, FetchRequest.PartitionData)],
    responseCallback: collection.Seq[(TopicIdPartition, FetchPartitionData)] => Unit,
    storeState: mutable.Map[TopicPartition, Seq[MemoryRecords]],
    storeStateLock: Object
) extends DelayedOperation(params.maxWaitMs) {

  override def onExpiration(): Unit = {
    info(s"onExpire for CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
  }

  override def onComplete(): Unit = {
    info(s"onComplete for CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
    val fetchPartitionData = fetchInfos.map { case (topicIdPartition, _) =>
      storeStateLock.synchronized {
        val records = storeState.get(topicIdPartition.topicPartition())
        info(s"store state for partition $topicIdPartition is $records")
        val recordsToReturn = if (records.isEmpty || records.get.isEmpty) MemoryRecords.EMPTY else records.get.last
        topicIdPartition -> new FetchPartitionData(
          Errors.NONE,
          0,
          0,
          recordsToReturn,
          Optional.empty(),
          OptionalLong.empty(),
          Optional.empty(),
          OptionalInt.empty(),
          false
        )
      }
    }
    responseCallback(fetchPartitionData)
  }

  override def tryComplete(): Boolean = {
    info(s"Trying to complete CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
    false
  }
}
