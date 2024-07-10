package kafka.server

import org.apache.kafka.common.TopicIdPartition
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.requests.FetchRequest
import org.apache.kafka.storage.internals.log.{FetchParams, FetchPartitionData}

import java.util.{Optional, OptionalInt, OptionalLong}

class CustomMessageStoreDelayedFetch(
    params: FetchParams,
    fetchInfos: collection.Seq[(TopicIdPartition, FetchRequest.PartitionData)],
    responseCallback: collection.Seq[(TopicIdPartition, FetchPartitionData)] => Unit
) extends DelayedOperation(params.maxWaitMs) {
  /**
   * Call-back to execute when a delayed operation gets expired and hence forced to complete.
   */
  override def onExpiration(): Unit = {
    info(s"onExpire for CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
  }

  /**
   * Process for completing an operation; This function needs to be defined
   * in subclasses and will be called exactly once in forceComplete()
   */
  override def onComplete(): Unit = {
    info(s"onComplete for CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
    val fetchPartitionData = fetchInfos.map { case (topicIdPartition, _) =>
      topicIdPartition -> new FetchPartitionData(
        Errors.NONE,
        0,
        0,
        MemoryRecords.EMPTY,
        Optional.empty(),
        OptionalLong.empty(),
        Optional.empty(),
        OptionalInt.empty(),
        false
      )
    }
    responseCallback(fetchPartitionData)
  }

  /**
   * Try to complete the delayed operation by first checking if the operation
   * can be completed by now. If yes execute the completion logic by calling
   * forceComplete() and return true iff forceComplete returns true; otherwise return false
   *
   * This function needs to be defined in subclasses
   */
  override def tryComplete(): Boolean = {
    info(s"Trying to complete CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
    false
  }
}
