package service

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.ProducerMessage
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Merge,
  RunnableGraph,
  Sink,
  Source
}
import akka.stream.{ActorMaterializer, ClosedShape}
import configuration.Env
import db.{
  CatchupLockDataStore,
  ConsentFactDataStore,
  LastConsentFactDataStore,
  TenantDataStore
}
import messaging.KafkaMessageBroker
import models.{ConsentFact, ConsentFactCreated, ConsentFactUpdated}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.Logger
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}

case class Catchup(consentFact: ConsentFact, lastConsentFact: ConsentFact) {
  val isCreation: Boolean = consentFact._id == lastConsentFact._id
}

class CatchupNioEventService(tenantDataStore: TenantDataStore,
                             lastConsentFactDataStore: LastConsentFactDataStore,
                             consentFactDataStore: ConsentFactDataStore,
                             catchupLockDataStore: CatchupLockDataStore,
                             kafkaMessageBroker: KafkaMessageBroker,
                             env: Env)(
    implicit val executionContext: ExecutionContext,
    system: ActorSystem) {

  implicit val materializer: ActorMaterializer = ActorMaterializer()(system)

  def getUnsendConsentFactAsSource(
      tenant: String): Source[Catchup, Future[Any]] =
    Source
      .fromFutureSource(
        consentFactDataStore
          .streamUnSendToKafka(tenant)
      )
      .mapMaterializedValue(_.flatten)
      .mapAsync(1) { consentFact =>
        val eventualMaybeFact: Future[Option[ConsentFact]] =
          lastConsentFactDataStore
            .findOneByUserId(tenant, consentFact.userId)

        eventualMaybeFact.map(_.map(r => Catchup(consentFact, r)))
      }
      .mapConcat(_.toList)

  def getUnrelevantConsentFactsFlow: Flow[Catchup, Catchup, NotUsed] =
    Flow[Catchup]
      .filterNot { catchup =>
        catchup.lastConsentFact.lastUpdateSystem
          .isEqual(catchup.consentFact.lastUpdateSystem)
      }

  def getRelevantConsentFactsAsFlow(
      tenant: String): Flow[Catchup, Catchup, NotUsed] =
    Flow[Catchup]
      .filter { catchup =>
        env.config.kafka.catchUpEvents.strategy == "All" ||
        catchup.lastConsentFact.lastUpdateSystem
          .isEqual(catchup.consentFact.lastUpdateSystem)
      }

  def resendNioEventsFlow(tenant: String): Flow[Catchup, Catchup, NotUsed] = {

    Flow[Catchup]
      .map { catchup =>
        val event = if (catchup.isCreation) {
          ConsentFactCreated(tenant = tenant,
                             payload = catchup.lastConsentFact,
                             author = "EVENT_CATCHUP",
                             metadata = None)
        } else {
          ConsentFactUpdated(tenant = tenant,
                             oldValue = catchup.consentFact,
                             payload = catchup.lastConsentFact,
                             author = "EVENT_CATCHUP",
                             metadata = None)
        }

        ProducerMessage.Message[String, String, Catchup](
          new ProducerRecord(env.config.kafka.topic,
                             event.shardId,
                             event.asJson.toString()),
          catchup
        )
      }
      .via(Producer.flexiFlow(kafkaMessageBroker.producerSettings))
      .map(_.passThrough)
      .via(unflagConsentFactsFlow(tenant))
  }

  def unflagConsentFactsFlow(tenant: String): Flow[Catchup, Catchup, NotUsed] =
    Flow[Catchup]
      .grouped(200)
      .mapAsync(1) { catchups =>
        consentFactDataStore
          .markAsSendToKafka(
            tenant,
            catchups.map(_.consentFact._id)
          )
          .map(_ => catchups)

      }
      .mapConcat(_.toList)

  def catchupNioEventScheduler() =
    if (env.env == "prod") {
      tenantDataStore.findAll().map { tenants =>
        tenants.map(tenant => {
          Logger.debug(s"start catchup scheduler for tenant ${tenant.key}")
          system.scheduler.schedule(
            env.config.kafka.catchUpEvents.delay,
            env.config.kafka.catchUpEvents.interval,
            new Runnable() {
              def run = {
                catchupLockDataStore
                  .findLock(tenant.key)
                  .map {
                    case Some(_) =>
                      Logger.debug(s"tenant ${tenant.key} already locked")
                    case None =>
                      catchupLockDataStore
                        .createLock(tenant.key)
                        .map {
                          case true =>
                            val g = RunnableGraph.fromGraph(GraphDSL.create() {
                              implicit builder: GraphDSL.Builder[NotUsed] =>
                                import GraphDSL.Implicits._

                                if (env.config.kafka.catchUpEvents.strategy == "Last") {
                                  val in: Source[Catchup, Future[Any]] =
                                    getUnsendConsentFactAsSource(tenant.key)
                                  val out = Sink.ignore
                                  val bcast = builder.add(Broadcast[Catchup](2))
                                  val merge = builder.add(Merge[Catchup](2))

                                  val markAsPublishedUnreleavant
                                    : Flow[Catchup, Catchup, NotUsed] =
                                    getUnrelevantConsentFactsFlow
                                      .via(unflagConsentFactsFlow(tenant.key))
                                      .filter(_ => false)

                                  val filterRelevant
                                    : Flow[Catchup, Catchup, NotUsed] =
                                    Flow[Catchup].via(
                                      getRelevantConsentFactsAsFlow(tenant.key))

                                  in ~> bcast ~> markAsPublishedUnreleavant ~> merge ~> resendNioEventsFlow(
                                    tenant.key) ~> out
                                  bcast ~> filterRelevant ~> merge
                                } else {
                                  val in: Source[Catchup, Future[Any]] =
                                    getUnsendConsentFactAsSource(tenant.key)
                                  val out = Sink.ignore

                                  in ~> resendNioEventsFlow(tenant.key) ~> out
                                }

                                ClosedShape
                            })

                            g.run()
                          case false => ()
                        }
                  }
              }
            }
          )
        })
      }
    }
}
