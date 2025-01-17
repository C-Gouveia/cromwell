package cromwell.backend.impl.bcs

import com.typesafe.config.{Config, ConfigFactory}
import common.collections.EnhancedCollections._
import cromwell.backend.BackendSpec.buildWdlWorkflowDescriptor
import cromwell.backend.validation.ContinueOnReturnCodeSet
import cromwell.backend.{BackendConfigurationDescriptor, BackendJobDescriptorKey, BackendWorkflowDescriptor, RuntimeAttributeDefinition}
import cromwell.core.{TestKitSuite, WorkflowOptions}
import cromwell.filesystems.oss.OssPathBuilder
import cromwell.filesystems.oss.nio.DefaultOssStorageConfiguration
import cromwell.util.SampleWdl
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.slf4j.helpers.NOPLogger
import spray.json.{JsObject, JsString}
import wom.values.WomValue

object BcsTestUtilSpec {

  val DefaultRunAttributesString: String =
    """
      |default-runtime-attributes {
      |  failOnStderr: false
      |  continueOnReturnCode: 0
      |  cluster: "cls-mycluster"
      |  mounts: "oss://bcs-bucket/bcs-dir/ /home/inputs/ false"
      |  dockerTag: "ubuntu/latest oss://bcs-reg/ubuntu/"
      |  docker: "registry.cn-beijing.aliyuncs.com/test/testubuntu:0.1"
      |  userData: "key value"
      |  reserveOnFail: true
      |  autoReleaseJob: true
      |  verbose: false
      |  systemDisk: "cloud 50"
      |  dataDisk: "cloud 250 /home/data/"
      |  timeout: 3000
      |  vpc: "192.168.0.0/16 vpc-xxxx"
      |  tag: "jobTag"
      |  imageId: "img-ubuntu-vpc"
      |  isv: "test-isv"
      |}
    """.stripMargin

  val BcsBackendConfigString: String =
    s"""
      |root = "oss://your-bucket/cromwell-exe"
      |dockerRoot = "/cromwell-executions"
      |region = ""
      |
      |access-id = ""
      |access-key = ""
      |security-token = ""
      |
      |filesystems {
      |  oss {
      |    auth {
      |        endpoint = ""
      |        access-id = ""
      |        access-key = ""
      |        security-token = ""
      |    }
      |    caching {
      |        duplication-strategy = "reference"
      |    }
      |  }
      |}
      |
      |$DefaultRunAttributesString
      |
      |""".stripMargin

  val BcsBackendConfigWithoutDefaultString: String =
    s"""
       |root = "oss://your-bucket/cromwell-exe"
       |dockerRoot = "/cromwell-executions"
       |region = ""
       |
       |access-id = ""
       |access-key = ""
       |security-token = ""
       |
       |filesystems {
       |  oss {
       |    auth {
       |        endpoint = ""
       |        access-id = ""
       |        access-key = ""
       |        security-token = ""
       |    }
       |  }
       |}
       |
       |""".stripMargin

  val BcsGlobalConfigString: String =
    s"""
      |backend {
      |  default = "BCS"
      |  providers {
      |    BCS {
      |      actor-factory = "cromwell.backend.impl.bcs.BcsBackendLifecycleActorFactory"
      |      config {
      |      $BcsBackendConfigString
      |      }
      |    }
      |  }
      |}
      |
      |""".stripMargin

  val BcsBackendConfig: Config = ConfigFactory.parseString(BcsBackendConfigString)
  val BcsGlobalConfig: Config = ConfigFactory.parseString(BcsGlobalConfigString)
  val BcsBackendConfigWithoutDefault: Config = ConfigFactory.parseString(BcsBackendConfigWithoutDefaultString)
  val BcsBackendConfigurationDescriptor: BackendConfigurationDescriptor =
    BackendConfigurationDescriptor(BcsBackendConfig, BcsGlobalConfig)
  val BcsBackendConfigurationWithoutDefaultDescriptor: BackendConfigurationDescriptor =
    BackendConfigurationDescriptor(BcsBackendConfigWithoutDefault, BcsGlobalConfig)
  val EmptyWorkflowOption: WorkflowOptions = WorkflowOptions.fromMap(Map.empty).get
}

trait BcsTestUtilSpec extends TestKitSuite with AnyFlatSpecLike with Matchers with BeforeAndAfter {

  before {
    BcsMount.pathBuilders = List(mockPathBuilder)
  }

  val jobId = "test-bcs-job"
  val mockOssConf: DefaultOssStorageConfiguration =
    DefaultOssStorageConfiguration("oss.aliyuncs.com", "test-id", "test-key")
  lazy val mockPathBuilder: OssPathBuilder = OssPathBuilder(mockOssConf)
  val mockPathBuilders = List(mockPathBuilder)
  lazy val workflowDescriptor: BackendWorkflowDescriptor =  buildWdlWorkflowDescriptor(
    SampleWdl.HelloWorld.workflowSource(),
    inputFileAsJson = Option(JsObject(SampleWdl.HelloWorld.rawInputs.safeMapValues(JsString.apply)).compactPrint)
  )
  lazy val jobKey: BackendJobDescriptorKey = {
    val call = workflowDescriptor.callable.taskCallNodes.head
    BackendJobDescriptorKey(call, None, 1)
  }


  val expectedContinueOnReturn: ContinueOnReturnCodeSet = ContinueOnReturnCodeSet(Set(0))
  val expectedDockerTag: Option[BcsDockerWithPath] =
    Option(BcsDockerWithPath("ubuntu/latest", "oss://bcs-reg/ubuntu/"))
  val expectedDocker: Option[BcsDockerWithoutPath] =
    Option(BcsDockerWithoutPath("registry.cn-beijing.aliyuncs.com/test/testubuntu:0.1"))
  val expectedFailOnStderr = false
  val expectedUserData: Option[Vector[BcsUserData]] = Option(Vector(new BcsUserData("key", "value")))
  val expectedMounts: Option[Vector[BcsInputMount]] =
    Option(Vector(
      BcsInputMount(
        src = Left(mockPathBuilder.build("oss://bcs-bucket/bcs-dir/").get),
        dest = Right("/home/inputs/"),
        writeSupport = false,
      )
    ))
  val expectedCluster: Option[Left[String, Nothing]] = Option(Left("cls-mycluster"))
  val expectedImageId: Option[String] = Option("img-ubuntu-vpc")
  val expectedSystemDisk: Option[BcsSystemDisk] = Option(BcsSystemDisk("cloud", 50))
  val expectedDataDisk: Option[BcsDataDisk] = Option(BcsDataDisk("cloud", 250, "/home/data/"))

  val expectedReserveOnFail: Option[Boolean] = Option(true)
  val expectedAutoRelease: Option[Boolean] = Option(true)
  val expectedTimeout: Option[Int] = Option(3000)
  val expectedVerbose: Option[Boolean] = Option(false)
  val expectedVpc: Option[BcsVpcConfiguration] =
    Option(BcsVpcConfiguration(Option("192.168.0.0/16"), Option("vpc-xxxx")))
  val expectedTag: Option[String] = Option("jobTag")
  val expectedIsv: Option[String] = Option("test-isv")


  val expectedRuntimeAttributes = new BcsRuntimeAttributes(expectedContinueOnReturn, expectedDockerTag, expectedDocker, expectedFailOnStderr,  expectedMounts, expectedUserData, expectedCluster,
    expectedImageId, expectedSystemDisk, expectedDataDisk, expectedReserveOnFail, expectedAutoRelease, expectedTimeout, expectedVerbose, expectedVpc, expectedTag, expectedIsv)


  protected def createBcsRuntimeAttributes(runtimeAttributes: Map[String, WomValue]): BcsRuntimeAttributes = {
    val builder = BcsRuntimeAttributes.runtimeAttributesBuilder(BcsTestUtilSpec.BcsBackendConfigurationDescriptor.backendRuntimeAttributesConfig)
    val default = RuntimeAttributeDefinition.addDefaultsToAttributes(
      builder.definitions.toSet, BcsTestUtilSpec.EmptyWorkflowOption)(runtimeAttributes)
    val validated = builder.build(default, NOPLogger.NOP_LOGGER)
    BcsRuntimeAttributes(validated, BcsTestUtilSpec.BcsBackendConfigurationDescriptor.backendRuntimeAttributesConfig)
  }
}
