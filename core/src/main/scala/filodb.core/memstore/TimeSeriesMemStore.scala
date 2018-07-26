package filodb.core.memstore

import scala.collection.mutable.HashMap
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import monix.execution.{CancelableFuture, Scheduler}
import monix.reactive.Observable
import org.jctools.maps.NonBlockingHashMapLong

import filodb.core.{DatasetRef, Response, Types}
import filodb.core.metadata.Dataset
import filodb.core.store._
import filodb.memory.format.ZeroCopyUTF8String

class TimeSeriesMemStore(config: Config, val store: ColumnStore, val metastore: MetaStore,
                         evictionPolicy: Option[PartitionEvictionPolicy] = None)
                        (implicit val ec: ExecutionContext)
extends MemStore with StrictLogging {
  import collection.JavaConverters._

  type Shards = NonBlockingHashMapLong[TimeSeriesShard]
  private val datasets = new HashMap[DatasetRef, Shards]

  val stats = new ChunkSourceStats

  private val numParallelFlushes = config.getInt("memstore.flush-task-parallelism")

  private val partEvictionPolicy = evictionPolicy.getOrElse {
    new HeapPercentageEvictionPolicy(config.getInt("memstore.min-heap-free-percentage"))
  }

  // TODO: Change the API to return Unit Or ShardAlreadySetup, instead of throwing.  Make idempotent.
  def setup(dataset: Dataset, shard: Int, storeConf: StoreConfig): Unit = synchronized {
    val shards = datasets.getOrElseUpdate(dataset.ref, new NonBlockingHashMapLong[TimeSeriesShard](32, false))
    if (shards contains shard) {
      throw ShardAlreadySetup(dataset.ref, shard)
    } else {
      val tsdb = new OnDemandPagingShard(dataset, storeConf, shard, store, metastore, partEvictionPolicy)
      shards.put(shard, tsdb)
    }
  }

  /**
    * WARNING: use only for testing. Not performant
    */
  def commitIndexBlocking(dataset: DatasetRef): Unit =
    datasets.get(dataset).foreach(_.values().asScala.foreach(_.commitPartKeyIndexBlocking()))

  /**
    * Retrieve shard for given dataset and shard number as an Option
    */
  private[core] def getShard(dataset: DatasetRef, shard: Int): Option[TimeSeriesShard] =
    datasets.get(dataset).flatMap { shards => Option(shards.get(shard)) }

  /**
    * Retrieve shard for given dataset and shard number. Raises exception if
    * the shard is not setup.
    */
  private[filodb] def getShardE(dataset: DatasetRef, shard: Int): TimeSeriesShard = {
    datasets.get(dataset)
            .flatMap(shards => Option(shards.get(shard)))
            .getOrElse(throw new IllegalArgumentException(s"Dataset $dataset and shard $shard have not been set up"))
  }

  def ingest(dataset: DatasetRef, shard: Int, data: SomeData): Unit =
    getShardE(dataset, shard).ingest(data.records, data.offset)

  // Should only be called once per dataset/shard
  def ingestStream(dataset: DatasetRef,
                   shardNum: Int,
                   stream: Observable[SomeData],
                   flushStream: Observable[FlushCommand] = FlushStream.empty,
                   diskTimeToLiveSeconds: Int = 259200,
                   flushIndex: Boolean = true)
                  (errHandler: Throwable => Unit)
                  (implicit sched: Scheduler): CancelableFuture[Unit] = {
    // start flushing index now that we have recovered
    if (flushIndex) getShard(dataset, shardNum).map(_.startFlushingIndex())
    ingestStream(dataset, shardNum, Observable.merge(stream, flushStream), diskTimeToLiveSeconds)(errHandler)
  }

  // NOTE: Each ingestion message is a SomeData which has a RecordContainer, which can hold hundreds or thousands
  // of records each.  For this reason the object allocation of a SomeData and RecordContainer is not that bad.
  // If it turns out the batch size is small, consider using object pooling.
  def ingestStream(dataset: DatasetRef,
                   shardNum: Int,
                   combinedStream: Observable[DataOrCommand],
                   diskTimeToLiveSeconds: Int)
                  (errHandler: Throwable => Unit)
                  (implicit sched: Scheduler): CancelableFuture[Unit] = {
    val shard = getShardE(dataset, shardNum)
    combinedStream.map {
                    case d: SomeData => shard.ingest(d)
                                        None
                    // The write buffers for all partitions in a group are switched here, in line with ingestion
                    // stream.  This avoids concurrency issues and ensures that buffers for a group are switched
                    // at the same offset/watermark
                    case FlushCommand(group) => shard.switchGroupBuffers(group)
                                                shard.checkAndEvictPartitions()
                                                val firc = shard.prepareIndexTimeBucketForFlush(group)
                                                Some(FlushGroup(shard.shardNum,
                                                  group,
                                                  shard.latestOffset,
                                                  diskTimeToLiveSeconds, firc))
                  }.collect { case Some(flushGroup) => flushGroup }
                  .mapAsync(numParallelFlushes)(shard.createFlushTask _)
                  .foreach { x => }
                  .recover { case ex: Exception => errHandler(ex) }
  }

  // a more optimized ingest stream handler specifically for recovery
  // TODO: See if we can parallelize ingestion stream for even better throughput
  def recoverStream(dataset: DatasetRef,
                    shardNum: Int,
                    stream: Observable[SomeData],
                    checkpoints: Map[Int, Long],
                    reportingInterval: Long): Observable[Long] = {
    val shard = getShardE(dataset, shardNum)
    shard.setGroupWatermarks(checkpoints)
    var targetOffset = checkpoints.values.min + reportingInterval
    stream.map(shard.ingest(_))
          .collect {
            case offset if offset > targetOffset =>
              targetOffset += reportingInterval
              offset
          }
  }

  def indexNames(dataset: DatasetRef): Iterator[(String, Int)] =
    datasets.get(dataset).map { shards =>
      shards.entrySet.iterator.asScala.flatMap { entry =>
        val shardNum = entry.getKey.toInt
        entry.getValue.indexNames.map { s => (s, shardNum) }
      }
    }.getOrElse(Iterator.empty)

  def indexValues(dataset: DatasetRef, shard: Int, indexName: String): Iterator[ZeroCopyUTF8String] =
    getShard(dataset, shard).map(_.indexValues(indexName)).getOrElse(Iterator.empty)

  def numPartitions(dataset: DatasetRef, shard: Int): Int =
    getShard(dataset, shard).map(_.numActivePartitions).getOrElse(-1)

  def readRawPartitions(dataset: Dataset,
                        columnIDs: Seq[Types.ColumnId],
                        partMethod: PartitionScanMethod,
                        chunkMethod: ChunkScanMethod = AllChunkScan): Observable[RawPartData] = Observable.empty

  def scanPartitions(dataset: Dataset,
                     columnIDs: Seq[Types.ColumnId],
                     partMethod: PartitionScanMethod,
                     chunkMethod: ChunkScanMethod = AllChunkScan): Observable[FiloPartition] =
    datasets(dataset.ref).get(partMethod.shard).scanPartitions(columnIDs, partMethod, chunkMethod)

  def numRowsIngested(dataset: DatasetRef, shard: Int): Long =
    getShard(dataset, shard).map(_.numRowsIngested).getOrElse(-1L)

  def latestOffset(dataset: DatasetRef, shard: Int): Long =
    getShard(dataset, shard).get.latestOffset

  def shardMetrics(dataset: DatasetRef, shard: Int): TimeSeriesShardStats =
    getShard(dataset, shard).get.shardStats

  def activeShards(dataset: DatasetRef): Seq[Int] =
    datasets.get(dataset).map(_.keySet.asScala.map(_.toInt).toSeq).getOrElse(Nil)

  def getScanSplits(dataset: DatasetRef, splitsPerNode: Int = 1): Seq[ScanSplit] =
    activeShards(dataset).map(ShardSplit)

  def reset(): Unit = {
    datasets.clear()
    store.reset()
  }

  def truncate(dataset: DatasetRef): Future[Response] = {
    datasets.get(dataset).foreach(_.values.asScala.foreach(_.reset()))
    store.truncate(dataset)
  }

  // Release memory etc.
  def shutdown(): Unit = {
    datasets.values.foreach(_.values.asScala.foreach(_.shutdown()))
    datasets.values.foreach(_.values().asScala.foreach(_.closePartKeyIndex()))
    reset()
  }
}
