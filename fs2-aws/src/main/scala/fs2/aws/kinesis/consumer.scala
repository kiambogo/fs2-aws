package fs2.aws.kinesis

import java.util.UUID

import cats.effect.{ConcurrentEffect, IO, Timer}
import cats.implicits._
import fs2.{Pipe, RaiseThrowable, Sink, Stream}
import fs2.aws.internal._
import fs2.aws.internal.Exceptions.KinesisCheckpointException
import fs2.concurrent.Queue
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.kinesis.common.ConfigsBuilder
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.processor.ShardRecordProcessorFactory
import software.amazon.kinesis.retrieval.KinesisClientRecord

object consumer {
  private def defaultScheduler(
      recordProcessorFactory: ShardRecordProcessorFactory,
      settings: KinesisConsumerSettings
  ): Scheduler = {
    val kinesisClient: KinesisAsyncClient =
      KinesisAsyncClient
        .builder()
        .region(settings.region)
        .httpClientBuilder(
          NettyNioAsyncHttpClient.builder().maxConcurrency(settings.maxConcurrency))
        .build()
    val dynamoClient: DynamoDbAsyncClient =
      DynamoDbAsyncClient.builder().region(settings.region).build()
    val cloudWatchClient: CloudWatchAsyncClient =
      CloudWatchAsyncClient.builder().region(settings.region).build()

    val configsBuilder: ConfigsBuilder = new ConfigsBuilder(
      settings.streamName,
      settings.appName,
      kinesisClient,
      dynamoClient,
      cloudWatchClient,
      UUID.randomUUID().toString,
      recordProcessorFactory
    )

    new Scheduler(
      configsBuilder.checkpointConfig(),
      configsBuilder.coordinatorConfig(),
      configsBuilder.leaseManagementConfig(),
      configsBuilder.lifecycleConfig(),
      configsBuilder.metricsConfig(),
      configsBuilder.processorConfig(),
      configsBuilder.retrievalConfig()
    )
  }

  /** Intialize a worker and start streaming records from a Kinesis stream
    * On stream finish (due to error or other), worker will be shutdown
    *
    *  @tparam F effect type of the fs2 stream
    *  @param appName name of the Kinesis application. Used by KCL when resharding
    *  @param streamName name of the Kinesis stream to consume from
    *  @return an infinite fs2 Stream that emits Kinesis Records
    */
  def readFromKinesisStream[F[_]](
      appName: String,
      streamName: String
  )(implicit F: ConcurrentEffect[F], rt: RaiseThrowable[F]): Stream[F, CommittableRecord] = {
    KinesisConsumerSettings(streamName, appName) match {
      case Right(config) => readFromKinesisStream(config)
      case Left(err)     => Stream.raiseError(err)
    }
  }

  /** Intialize a worker and start streaming records from a Kinesis stream
    * On stream finish (due to error or other), worker will be shutdown
    *
    *  @tparam F effect type of the fs2 stream
    *  @param consumerConfig configuration parameters for the KCL
    *  @return an infinite fs2 Stream that emits Kinesis Records
    */
  def readFromKinesisStream[F[_]](consumerConfig: KinesisConsumerSettings)(
      implicit F: ConcurrentEffect[F]): Stream[F, CommittableRecord] = {
    readFromKinesisStream(defaultScheduler(_, consumerConfig), consumerConfig)
  }

  /** Intialize a worker and start streaming records from a Kinesis stream
    * On stream finish (due to error or other), worker will be shutdown
    *
    *  @tparam F effect type of the fs2 stream
    *  @param workerFactory function to create a Worker from a IRecordProcessorFactory
    *  @param streamConfig configuration for the internal stream
    *  @return an infinite fs2 Stream that emits Kinesis Records
    */
  private[aws] def readFromKinesisStream[F[_]](
      workerFactory: => ShardRecordProcessorFactory => Scheduler,
      streamConfig: KinesisConsumerSettings
  )(implicit F: ConcurrentEffect[F]): Stream[F, CommittableRecord] = {

    // Initialize a KCL worker which appends to the internal stream queue on message receipt
    def instantiateWorker(queue: Queue[F, CommittableRecord]): F[Scheduler] = F.delay {
      workerFactory(
        () =>
          new RecordProcessor(
            record => F.runAsync(queue.enqueue1(record))(_ => IO.unit).unsafeRunSync,
            streamConfig.terminateGracePeriod
        )
      )
    }

    // Instantiate a new bounded queue and concurrently run the queue populator
    // Expose the elements by dequeuing the internal buffer
    for {
      buffer <- Stream.eval(Queue.bounded[F, CommittableRecord](streamConfig.bufferSize))
      worker = instantiateWorker(buffer)
      stream <- buffer.dequeue concurrently Stream.eval(worker.map(_.run)) onFinalize worker.map(
        _.shutdown)
    } yield stream
  }

  /** Pipe to checkpoint records in Kinesis, marking them as processed
    * Groups records by shard id, so that each shard is subject to its own clustering of records
    * After accumulating maxBatchSize or reaching maxBatchWait for a respective shard, the latest record is checkpointed
    * By design, all records prior to the checkpointed record are also checkpointed in Kinesis
    *
    *  @tparam F effect type of the fs2 stream
    *  @param checkpointSettings configure maxBatchSize and maxBatchWait time before triggering a checkpoint
    *  @return a stream of Record types representing checkpointed messages
    */
  def checkpointRecords[F[_]](
      checkpointSettings: KinesisCheckpointSettings = KinesisCheckpointSettings.defaultInstance,
      parallelism: Int = 10
  )(implicit F: ConcurrentEffect[F],
    timer: Timer[F]): Pipe[F, CommittableRecord, KinesisClientRecord] = {
    _.through(groupBy(r => F.delay(r.shardId))).map {
      case (k, st) =>
        st.groupWithin(checkpointSettings.maxBatchSize, checkpointSettings.maxBatchWait)
          .map(_.toList.max)
          .mapAsync(parallelism) { cr =>
            F.async[KinesisClientRecord] { cb =>
              if (cr.canCheckpoint) {
                cr.checkpoint()
                cb(Right(cr.record))
              } else {
                cb(
                  Left(KinesisCheckpointException(
                    "Record processor has been shutdown and therefore cannot checkpoint records")))
              }
            }
          }
    }.parJoinUnbounded
  }

  /** Sink to checkpoint records in Kinesis, marking them as processed
    * Groups records by shard id, so that each shard is subject to its own clustering of records
    * After accumulating maxBatchSize or reaching maxBatchWait for a respective shard, the latest record is checkpointed
    * By design, all records prior to the checkpointed record are also checkpointed in Kinesis
    *
    *  @tparam F effect type of the fs2 stream
    *  @param checkpointSettings configure maxBatchSize and maxBatchWait time before triggering a checkpoint
    *  @return a Sink that accepts a stream of CommittableRecords
    */
  def checkpointRecords_[F[_]](
      checkpointSettings: KinesisCheckpointSettings = KinesisCheckpointSettings.defaultInstance
  )(implicit F: ConcurrentEffect[F], timer: Timer[F]): Sink[F, CommittableRecord] = {
    _.through(checkpointRecords(checkpointSettings))
      .map(_ => ())
  }
}
