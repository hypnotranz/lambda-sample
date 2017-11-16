package analyzer

import akka.actor._
import akka.pattern.ask
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.datastax.driver.core.Cluster
import com.datastax.driver.core.querybuilder.QueryBuilder
import lib._
import redis.RedisClient

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

object HistoryWriter {
  def props(cluster: Cluster, redisClient: RedisClient, analyzerOpt: Option[ActorRef])
           (implicit materializer: ActorMaterializer) =
    Props(classOf[HistoryWriter], cluster, redisClient, analyzerOpt, materializer)

  private final case object Tick
}

class HistoryWriter(cluster: Cluster, redisClient: RedisClient, analyzerOpt: Option[ActorRef])
                   (implicit materializer: ActorMaterializer)
  extends Actor with ActorLogging {
  import HistoryWriter._

  implicit val system: ActorSystem = context.system
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val logger: LoggingAdapter = log

  private val conf = Config.get
  implicit val timeout: Timeout = Timeout(conf.historyWriter.timeout.millis)

  private val session = cluster.connect(conf.cassandra.keyspace)

  private val lastTimestamp = collection.mutable.Map(
    conf.mqtt.sensors.asScala.map(a => (a, new java.util.Date())): _*
  )

  private var analyzers = analyzerOpt match {
    case Some(ref) => IndexedSeq(ref)
    case None => IndexedSeq()
  }
  var jobCounter = 0

  override def postStop(): Unit = {
    session.close()
  }

  override def receive: Receive = {
    case Tick =>
      val doNeedUpdate =
        for (sensor <- conf.mqtt.sensors.asScala)
          yield needUpdate(sensor)

      Future.sequence(doNeedUpdate).onComplete {
        case Success(force) =>
          if (force.exists(x => x)) {
            forceAnalyze()
          }
        case Failure(t) =>
          log.error("History writer error {}", t)
          forceAnalyze()
      }

    case Registration if !analyzers.contains(sender()) =>
      context watch sender()
      analyzers = analyzers :+ sender()

    case Terminated(a) =>
      analyzers = analyzers.filterNot(_ == a)
  }

  def forceAnalyze(): Unit = {
    if (analyzers.nonEmpty) {
      jobCounter += 1
      val analyzer = analyzers(jobCounter % analyzers.size)
      val serializer = new BinarySerializer()
      ask(analyzer, Analyze).mapTo[AllMeta] foreach { x =>
        for (meta <- x.entries) {
          val bytes = serializer.toBinary(meta)
          redisClient.hset(conf.fastAnalyzer.key, meta.name, bytes)
        }
      }
    }
  }

  def needUpdate(sensor: String): Future[Boolean] = {
    val serializer = new BinarySerializer()
    for {
      bytesOpt <- redisClient.hget(conf.fastAnalyzer.key, sensor)
    } yield {
      val force = bytesOpt map { bytes =>
        val meta = serializer.fromBinary(
          bytes.toArray,
          BinarySerializer.SensorMetaManifest
        ).asInstanceOf[SensorMeta]

        val notUpdatedYet = lastTimestamp(sensor) == meta.ts
        val statement = QueryBuilder.update(conf.historyWriter.table)
          .`with`(QueryBuilder.set("fast_anomaly", meta.fastAnomaly))
          .and(QueryBuilder.set("full_anomaly", meta.fullAnomaly))
          .and(QueryBuilder.set("avg_anomaly", meta.avgAnomaly))
          .where(QueryBuilder.eq("sensor", meta.name))
          .and(QueryBuilder.eq("ts", meta.ts))
        session.execute(statement)

        lastTimestamp(sensor) = meta.ts
        notUpdatedYet
      }
      force.getOrElse(true)
    }
  }

  system.scheduler.schedule(0.millis, conf.historyWriter.period.millis) {
    self ! Tick
  }
}
