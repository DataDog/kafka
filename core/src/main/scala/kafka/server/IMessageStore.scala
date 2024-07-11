package kafka.server

import org.apache.kafka.common.{TopicIdPartition, TopicPartition}
import org.apache.kafka.common.record.{MemoryRecords, RecordValidationStats}
import org.apache.kafka.common.requests.FetchRequest.PartitionData
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.storage.internals.log.{AppendOrigin, FetchParams, FetchPartitionData}

import java.util.concurrent.locks.Lock
import scala.collection.{Map, Seq}

trait IMessageStore {
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

  def fetchMessages(params: FetchParams,
                    fetchInfos: Seq[(TopicIdPartition, PartitionData)],
                    quota: ReplicaQuota,
                    responseCallback: Seq[(TopicIdPartition, FetchPartitionData)] => Unit): Unit
}
