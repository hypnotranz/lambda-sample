package mqtt

import akka.actor._
import akka.stream.ActorMaterializer

import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.querybuilder.QueryBuilder

import scala.io.StdIn

import lib._

object Consumer {
  def props(cluster: Cluster)(implicit materializer: ActorMaterializer) =
    Props(classOf[Consumer], cluster, materializer)

  final case class Arrived(message: MqttMessage)
}

class Consumer(cluster: Cluster)(implicit materializer: ActorMaterializer)
  extends Actor with ActorLogging {
  import Consumer._

  implicit private val system = context.system
  implicit private val executionContext = system.dispatcher

  val conf = Config.get
  val keyspace = conf.cassandra.keyspace
  val table = conf.cassandra.table

  val session = cluster.connect(keyspace)

  val broker = conf.mqtt.broker
  val id = MqttClient.generateClientId
  val persistence = new MemoryPersistence
  val factory = new EntryFactory(conf.mqtt.salt)
  val client = new MqttClient(broker, id, persistence)

  client.connect()
  client.subscribe(conf.mqtt.topic)

  val callback = new MqttCallback {
    override def messageArrived(topic: String, message: MqttMessage): Unit = {
      self ! Arrived(message)
    }

    override def connectionLost(cause: Throwable): Unit = {
      log.info("Connection lost", cause)
    }

    override def deliveryComplete(token: IMqttDeliveryToken): Unit = {
    }
  }

  client.setCallback(callback)

  override def postStop() = {
    client.disconnect
    session.close
  }

  override def receive: Receive = {
    case Arrived(message) =>
      log.debug(s"Message arrived: $message")
      try {
        val entry = factory.get(message.getPayload)

        val statement = QueryBuilder.update(table)
          .`with`(QueryBuilder.set("value", Integer.valueOf(entry.value)))
          .where(QueryBuilder.eq("sensor", entry.sensor))
          .and(QueryBuilder.eq("ts", System.currentTimeMillis))
        session.execute(statement)
      } catch {
        case e: Throwable => log.error(e, "Consumer error")
      }
  }
}
