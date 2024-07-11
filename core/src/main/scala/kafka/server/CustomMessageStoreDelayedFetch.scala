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
    val fetchPartitionData = fetchInfos.map { case (topicIdPartition, fetchInfo) =>
      storeStateLock.synchronized {
        val maybeRecords = storeState.get(topicIdPartition.topicPartition())
        if (maybeRecords.isEmpty || maybeRecords.get.isEmpty) {
          topicIdPartition -> new FetchPartitionData(
            Errors.NONE,
            0, // hwm
            0, // lso
            MemoryRecords.EMPTY,
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            OptionalInt.empty(),
            false
          )
        } else {
          val records = maybeRecords.get.toList
          // todo: should really be returning multiple records until fetchMaxBytes is filled, for
          //       now just return the memrecords at the requested offset
          info(s"have records $records")
          info(s"going to index in to get the record at ${fetchInfo.fetchOffset.toInt}")
          val toReturnRecords = records(fetchInfo.fetchOffset.toInt)
          info(s"going to return record $toReturnRecords")
          topicIdPartition -> new FetchPartitionData(
            Errors.NONE,
            records.size, // hwm
            0, // lso
            toReturnRecords,
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            OptionalInt.empty(),
            false
          )
        }
      }
    }
    responseCallback(fetchPartitionData)
  }

  override def tryComplete(): Boolean = {
    info(s"Trying to complete CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
    false
  }
}
