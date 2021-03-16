package cromwell.services.metadata.hybridcarbonite

import akka.actor.ActorRef
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import com.typesafe.config.ConfigFactory
import common.assertion.ManyTimes._
import common.validation.Validation._
import cromwell.core.retry.SimpleExponentialBackoff
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.services.metadata.MetadataArchiveStatus.{Archived, Unarchived}
import cromwell.services.metadata.MetadataService.{QueryForWorkflowsMatchingParameters, QueryMetadata, WorkflowQueryFailure, WorkflowQueryResponse, WorkflowQueryResult, WorkflowQuerySuccess}
import cromwell.services.metadata.hybridcarbonite.CarboniteWorkerActor.CarboniteWorkflowComplete
import cromwell.services.metadata.hybridcarbonite.CarbonitingMetadataFreezerActor.FreezeMetadata
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class CarboniteWorkerActorSpec extends TestKitSuite with AnyFlatSpecLike with Matchers {

  val serviceRegistryActor: TestProbe = TestProbe("serviceRegistryActor")
  val ioActor: TestProbe = TestProbe("ioActor")
  val carboniteFreezerActor: TestProbe = TestProbe("carboniteFreezerActor")

  val fasterBackOff: SimpleExponentialBackoff = SimpleExponentialBackoff(
    initialInterval = 10.millis,
    maxInterval = 100.millis,
    multiplier = 1,
    randomizationFactor = 0.0
  )

  val carboniterConfig: HybridCarboniteConfig = HybridCarboniteConfig.parseConfig(ConfigFactory.parseString(
    """{
      |   bucket = "carbonite-test-bucket"
      |   filesystems {
      |     gcs {
      |       # A reference to the auth to use for storing and retrieving metadata:
      |       auth = "application-default"
      |     }
      |   }
      |   metadata-freezing {
      |     initial-interval = 5 seconds
      |   }
      |}""".stripMargin
  )).unsafe("Make config file")

  val freezingConfig: ActiveMetadataFreezingConfig = carboniterConfig.freezingConfig.asInstanceOf[ActiveMetadataFreezingConfig]

  val carboniteWorkerActor: TestActorRef[MockCarboniteWorkerActor] = TestActorRef(new MockCarboniteWorkerActor(
    carboniterConfig,
    serviceRegistryActor.ref,
    ioActor.ref,
    carboniteFreezerActor.ref,
    fasterBackOff
  ))

  val workflowToCarbonite = "04c93860-ea0a-11e9-81b4-2a2ae2dbcce4"
  val queryMeta: QueryMetadata = QueryMetadata(Option(1), Option(1), Option(1))
  val queryResult: WorkflowQueryResult =
    WorkflowQueryResult(workflowToCarbonite, None, None, None, None, None, None, None, None, Unarchived)
  val queryResponse: WorkflowQueryResponse = WorkflowQueryResponse(Seq(queryResult), 1)
  val querySuccessResponse: WorkflowQuerySuccess = WorkflowQuerySuccess(queryResponse, Option(queryMeta))


  it should "carbonite workflow at intervals" in {
    10.times {
      // We might get noise from instrumentation. We can ignore that, but we expect the query to come through eventually:
      val expectedQueryParams = CarboniteWorkerActor.buildQueryParametersForWorkflowToCarboniteQuery
      serviceRegistryActor.fishForSpecificMessage(10.seconds) {
        case QueryForWorkflowsMatchingParameters(`expectedQueryParams`) => true
      }

      serviceRegistryActor.send(carboniteWorkerActor, querySuccessResponse)

      carboniteFreezerActor.expectMsg(FreezeMetadata(WorkflowId.fromString(workflowToCarbonite)))

      carboniteFreezerActor.send(carboniteWorkerActor, CarboniteWorkflowComplete(WorkflowId.fromString(workflowToCarbonite), Archived))
    }
  }

  it should "keep carboniting workflow at intervals despite the query failures" in {
    10.times {
      // We might get noise from instrumentation. We can ignore that, but we expect the query to come through eventually:
      val expectedQueryParams = CarboniteWorkerActor.buildQueryParametersForWorkflowToCarboniteQuery
      serviceRegistryActor.fishForSpecificMessage(10.seconds) {
        case QueryForWorkflowsMatchingParameters(`expectedQueryParams`) => true
      }

      EventFilter.error(start = "Error while querying workflow to carbonite, will retry.") intercept {
        serviceRegistryActor.send(carboniteWorkerActor, WorkflowQueryFailure(new RuntimeException("exception")))
      }

    }
  }
}

class MockCarboniteWorkerActor(carboniterConfig: HybridCarboniteConfig,
                               serviceRegistryActor: ActorRef,
                               ioActor: ActorRef,
                               override val carboniteFreezerActor: ActorRef,
                               override val backOff: SimpleExponentialBackoff)
  extends CarboniteWorkerActor(carboniterConfig.freezingConfig.asInstanceOf[ActiveMetadataFreezingConfig], carboniterConfig, serviceRegistryActor, ioActor) { }
