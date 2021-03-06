package filodb.downsampler

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import monix.execution.Scheduler
import monix.reactive.Observable
import scalaxy.loops._

import filodb.cassandra.FiloSessionProvider
import filodb.cassandra.columnstore.CassandraColumnStore
import filodb.core.{DatasetRef, ErrorResponse, Instance}
import filodb.core.binaryrecord2.RecordSchema
import filodb.core.downsample.{ChunkDownsampler, DoubleChunkDownsampler, HistChunkDownsampler, TimeChunkDownsampler}
import filodb.core.memstore.{PagedReadablePartition, TimeSeriesPartition, TimeSeriesShardStats}
import filodb.core.metadata.Schemas
import filodb.core.store.{AllChunkScan, ChunkSet, RawPartData, ReadablePartition}
import filodb.memory.format.{SeqRowReader, UnsafeUtils}

/**
  * This object maintains state during the processing of a batch of TSPartitions to downsample. Namely
  * a. The memory manager used for the paged partitions
  * b. The buffer pool used to ingest and chunk the downsampled data
  * c. Block store for overflow chunks that go beyond write buffers
  * d. Statistics
  * e. The Cassandra Store API from which to read raw data as well as write downsampled data
  *
  * It performs the operation of downsampling all partitions in the batch and writes downsampled data
  * into cassandra.
  *
  * All of the necessary params for the behavior are loaded from DownsampleSettings.
  */
object BatchDownsampler extends StrictLogging with Instance {

  import Utils._

  val settings = DownsamplerSettings

  private val readSched = Scheduler.io("cass-read-sched")
  private val writeSched = Scheduler.io("cass-write-sched")

  private val sessionProvider =
    settings.sessionProvider.map { p =>
      val clazz = createClass(p).get
      val args = Seq((classOf[Config] -> settings.cassandraConfig))
      createInstance[FiloSessionProvider](clazz, args).get
    }

  private[downsampler] val cassandraColStore =
    new CassandraColumnStore(settings.filodbConfig, readSched, sessionProvider)(writeSched)

  private val kamonTags = Map( "rawDataset" -> settings.rawDatasetName,
                               "owner" -> "BatchDownsampler")

  private val schemas = Schemas.fromConfig(settings.filodbConfig).get

  private val rawSchemas = settings.rawSchemaNames.map { s => schemas.schemas(s)}

  /**
    * Downsample Schemas
    */
  private val dsSchemas = settings.rawSchemaNames.map { s => schemas.schemas(s).downsample.get}

  /**
    * Chunk Downsamplers by Raw Schema Id
    */
  private val chunkDownsamplersByRawSchemaId = debox.Map.empty[Int, scala.Seq[ChunkDownsampler]]
  rawSchemas.foreach { s => chunkDownsamplersByRawSchemaId += s.schemaHash -> s.data.downsamplers }

  /**
    * Raw dataset from which we downsample data
    */
  private[downsampler] val rawDatasetRef = DatasetRef(settings.rawDatasetName)

  // FIXME * 2 exists to workaround an issue where we see underallocation for metaspan due to
  // possible mis-calculation of max block meta size.
  private val maxMetaSize = dsSchemas.map(_.data.blockMetaSize).max * 2

  /**
    * Datasets to which we write downsampled data. Keyed by Downsample resolution.
    */
  private val downsampleDatasetRefs = settings.downsampleResolutions.map { res =>
    res -> DatasetRef(s"${rawDatasetRef}_ds_${res.toMinutes}")
  }.toMap

  private val offHeapMem = new ThreadLocal[PerThreadOffHeapMemory]() {
    override def initialValue(): PerThreadOffHeapMemory = new PerThreadOffHeapMemory(rawSchemas, kamonTags, maxMetaSize)
  }

  private val shardStats = new TimeSeriesShardStats(rawDatasetRef, -1) // TODO fix

  /**
    * Downsample batch of raw partitions, and store downsampled chunks to cassandra
    */
  private[downsampler] def downsampleBatch(rawPartsBatch: Seq[RawPartData],
                                           userTimeStart: Long,
                                           userTimeEnd: Long) = {

    logger.debug(s"Starting to downsample batch of ${rawPartsBatch.size} partitions " +
      s"rawDataset=${settings.rawDatasetName} for " +
      s"userTimeStart=${millisToString(userTimeStart)} userTimeEnd=${millisToString(userTimeEnd)}")

    val downsampledChunksToPersist = MMap[FiniteDuration, Iterator[ChunkSet]]()
    settings.downsampleResolutions.foreach { res =>
      downsampledChunksToPersist(res) = Iterator.empty
    }
    val rawPartsToFree = ArrayBuffer[PagedReadablePartition]()
    val downsampledPartsPartsToFree = ArrayBuffer[TimeSeriesPartition]()
    try {
      rawPartsBatch.foreach { rawPart =>
        downsamplePart(rawPart, rawPartsToFree, downsampledPartsPartsToFree,
          downsampledChunksToPersist, userTimeStart, userTimeEnd)
      }
      persistDownsampledChunks(downsampledChunksToPersist)
    } finally {
      // reclaim all blocks
      offHeapMem.get().blockMemFactory.markUsedBlocksReclaimable()
      // free partitions
      rawPartsToFree.foreach(_.free())
      rawPartsToFree.clear()
      downsampledPartsPartsToFree.foreach(_.shutdown())
      downsampledPartsPartsToFree.clear()
    }

    logger.info(s"Finished iterating through and downsampling batch of ${rawPartsBatch.size} " +
      s"partitions in current executor")
  }

  /**
    * Creates new downsample partitions per per the resolutions
    * * specified by `bufferPools`.
    * Downsamples all chunks in `partToDownsample` per the resolutions and stores
    * downsampled data into the newly created partition.
    *
    * NOTE THAT THE DOWNSAMPLE PARTITIONS NEED TO BE FREED/SHUT DOWN BY THE CALLER ONCE CHUNKS ARE PERSISTED
    *
    * @param rawPartsToFree raw partitions that need to be freed are added to this mutable list
    * @param downsampledPartsPartsToFree downsample partitions to be freed are added to this mutable list
    * @param downsampledChunksToPersist downsample chunks to persist are added to this mutable map
    */
  private[downsampler] def downsamplePart(rawPart: RawPartData,
                                          rawPartsToFree: ArrayBuffer[PagedReadablePartition],
                                          downsampledPartsPartsToFree: ArrayBuffer[TimeSeriesPartition],
                                          downsampledChunksToPersist: MMap[FiniteDuration, Iterator[ChunkSet]],
                                          userTimeStart: Long,
                                          userTimeEnd: Long) = {
    val rawSchemaId = RecordSchema.schemaID(rawPart.partitionKey, UnsafeUtils.arayOffset)
    val rawPartSchema = schemas(rawSchemaId)
    rawPartSchema.downsample match {
      case Some(downsampleSchema) =>
        logger.debug(s"Downsampling partition ${rawPartSchema.partKeySchema.stringify(rawPart.partitionKey)} ")

        val rawReadablePart = new PagedReadablePartition(rawPartSchema, 0, 0,
          rawPart, offHeapMem.get().nativeMemoryManager)
        val bufferPool = offHeapMem.get().bufferPools(rawSchemaId)
        val downsamplers = chunkDownsamplersByRawSchemaId(rawSchemaId)

        val downsampledParts = settings.downsampleResolutions.map { res =>
          val part = new TimeSeriesPartition(0, downsampleSchema, rawReadablePart.partitionKey,
                                            0, bufferPool, shardStats, offHeapMem.get().nativeMemoryManager, 1)
          res -> part
        }.toMap

        downsampleChunks(rawReadablePart, downsamplers, downsampledParts, userTimeStart, userTimeEnd)

        rawPartsToFree += rawReadablePart
        downsampledPartsPartsToFree ++= downsampledParts.values

        downsampledParts.foreach { case (res, dsPartition) =>
          dsPartition.switchBuffers(offHeapMem.get().blockMemFactory, true)
          downsampledChunksToPersist(res) ++= dsPartition.makeFlushChunks(offHeapMem.get().blockMemFactory)
        }
      case None =>
        logger.warn(s"Encountered partition ${rawPartSchema.partKeySchema.stringify(rawPart.partitionKey)}" +
          s" which does not have a downsample schema")
    }
  }

  /**
    * Downsample chunks in a partition, ingest the downsampled data into downsampled partitions
    *
    * @param rawPartToDownsample raw partition to downsample
    * @param downsamplers chunk downsamplers to use to downsample
    * @param downsampledParts the downsample parts in which to ingest downsampled data
    */
  // scalastyle:off method.length
  private def downsampleChunks(rawPartToDownsample: ReadablePartition,
                               downsamplers: Seq[ChunkDownsampler],
                               downsampledParts: Map[FiniteDuration, TimeSeriesPartition],
                               userTimeStart: Long,
                               userTimeEnd: Long) = {
    val timestampCol = 0
    val rawChunksets = rawPartToDownsample.infos(AllChunkScan)

    // TODO create a rowReader that will not box the vals below
    val downsampleRow = new Array[Any](downsamplers.size)
    val downsampleRowReader = SeqRowReader(downsampleRow)

    while (rawChunksets.hasNext) {
      val chunkset = rawChunksets.nextInfo
      val startTime = chunkset.startTime
      val endTime = chunkset.endTime
      val vecPtr = chunkset.vectorPtr(timestampCol)
      val tsReader = rawPartToDownsample.chunkReader(timestampCol, vecPtr).asLongReader

      // for each downsample resolution
      downsampledParts.foreach { case (resolution, part) =>
        val resMillis = resolution.toMillis
        // A sample exactly for 5pm downsampled 5-minutely should fall in the period 4:55:00:001pm to 5:00:00:000pm.
        // Hence subtract - 1 below from chunk startTime to find the first downsample period.
        // + 1 is needed since the startTime is inclusive. We don't want pStart to be 4:55:00:000;
        // instead we want 4:55:00:001
        var pStart = ((startTime - 1) / resMillis) * resMillis + 1
        var pEnd = pStart + resMillis // end is inclusive
        // for each downsample period
        while (pStart <= endTime) {
          if (pEnd >= userTimeStart && pEnd <= userTimeEnd) {
            // fix the boundary row numbers for the downsample period by looking up the timestamp column
            val startRowNum = tsReader.binarySearch(vecPtr, pStart) & 0x7fffffff
            val endRowNum = Math.min(tsReader.ceilingIndex(vecPtr, pEnd), chunkset.numRows - 1)

            // for each downsampler, add downsample column value
            for {col <- downsamplers.indices optimized} {
              val downsampler = downsamplers(col)
              downsampler match {
                case d: TimeChunkDownsampler =>
                  downsampleRow(col) = d.downsampleChunk(rawPartToDownsample, chunkset, startRowNum, endRowNum)
                case d: DoubleChunkDownsampler =>
                  downsampleRow(col) = d.downsampleChunk(rawPartToDownsample, chunkset, startRowNum, endRowNum)
                case h: HistChunkDownsampler =>
                  downsampleRow(col) = h.downsampleChunk(rawPartToDownsample, chunkset, startRowNum, endRowNum)
                    .serialize()
              }
            }
            logger.trace(s"Ingesting into part=${part.hashCode}: $downsampleRow")
            //use the userTimeStart as ingestionTime
            part.ingest(userTimeStart, downsampleRowReader, offHeapMem.get().blockMemFactory)
          }
          pStart += resMillis
          pEnd += resMillis
        }
      }
    }
  }

  /**
    * Persist chunks in `downsampledChunksToPersist` to Cassandra.
    */
  private[downsampler] def persistDownsampledChunks(
                                    downsampledChunksToPersist: MMap[FiniteDuration, Iterator[ChunkSet]]): Unit = {
    // write all chunks to cassandra
    val writeFut = downsampledChunksToPersist.map { case (res, chunks) =>
      cassandraColStore.write(downsampleDatasetRefs(res),
        Observable.fromIterator(chunks), settings.ttlByResolution(res))
    }

    writeFut.foreach { fut =>
      val response = Await.result(fut, settings.cassWriteTimeout)
      logger.debug(s"Got message $response for cassandra write call")
      if (response.isInstanceOf[ErrorResponse])
        throw new IllegalStateException(s"Got response $response when writing to Cassandra")
    }
  }

}
