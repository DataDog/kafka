package kafka.server

import org.apache.kafka.common.{TopicIdPartition, TopicPartition}
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record.MemoryRecords
import org.apache.kafka.common.requests.FetchRequest
import org.apache.kafka.storage.internals.log.{FetchParams, FetchPartitionData}

import java.util.{Optional, OptionalInt, OptionalLong}
import scala.collection.mutable
import scala.jdk.CollectionConverters.IterableHasAsScala

class CustomMessageStoreDelayedFetch(
    params: FetchParams,
    fetchInfos: collection.Seq[(TopicIdPartition, FetchRequest.PartitionData)],
    responseCallback: collection.Seq[(TopicIdPartition, FetchPartitionData)] => Unit,
    storeState: mutable.Map[TopicPartition, Seq[MemoryRecords]],
    storeStateLock: Object
) extends DelayedOperation(params.maxWaitMs) {

  override def onExpiration(): Unit = {}

  override def onComplete(): Unit = {
    info(s"onComplete for CustomMessageStoredDelayedFetch with params $params, fetchInfos $fetchInfos")
    val fetchPartitionData = storeStateLock.synchronized {
      fetchInfos.map { case (topicIdPartition, fetchInfo) =>
        val maybeRecords = storeState.get(topicIdPartition.topicPartition())
        val partitionData = if (maybeRecords.isEmpty || maybeRecords.get.isEmpty) {
          new FetchPartitionData(
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
          //       now just return the records at the requested offset
          val toReturnRecords = records(fetchInfo.fetchOffset.toInt)
          new FetchPartitionData(
            Errors.NONE,
            records.size, // high water mark
            0, // log start offset
            toReturnRecords,
            Optional.empty(),
            OptionalLong.of(records.size), // last stable offset == high water mark since no transactions
            Optional.empty(),
            OptionalInt.empty(),
            false
          )
        }
        info(s"appending $partitionData for $topicIdPartition")
        info(s"batches that are coming")
        partitionData.records.batches().asScala.foreach {batch => info(s"batch $batch")}
        info(s"records that are coming")
        partitionData.records.records().asScala.foreach {record => info(s"record $record")}
        topicIdPartition -> partitionData
      }
    }
    info(s"Calling response callback with $fetchPartitionData")
    responseCallback(fetchPartitionData)
  }

  override def tryComplete(): Boolean = {
    false
  }
}
