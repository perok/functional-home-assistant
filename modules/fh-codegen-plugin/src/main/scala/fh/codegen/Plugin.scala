package fh.codegen

//import scala.meta.* // scalameta for code generation. does not support dotty
import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.effect.*
import cats.effect.syntax.all.*
import org.http4s.Uri
import fh.api.FHApi

object Plugin extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    for {
      haServer <- Uri.fromString(args(1)).liftTo[IO]
      outputDirectory = args(0)
      haSecret = args(2)
      _ <- program(outputDirectory, haServer, haSecret).use_
    } yield ExitCode.Success

  // TODO Get all sensors

  // TODO code gen
  // TODO create ingtegration: https://developers.home-assistant.io/docs/creating_component_index https://netdaemon.xyz/
  //

  def program(
      outputDirectory: String,
      api: Uri,
      secretToken: String
  ): Resource[IO, Unit] =
    for {
      api <- FHApi.from(api, secretToken)
      // _ <- service.postServiceApi("", "", "hello").toResource
      // _ <- hello.testit(service).debug("operatin").toResource
      (state, services) <- (
        api.getStates,
        api.getServices
      ).parTupled.toResource

      allEntities <- api.configEntityRegistryList.debug("entities").toResource

      allDevices <- api.configDeviceRegistryList.toResource

      allTriggers <- allDevices.values.toSeq
        .parTraverseN(10) { device =>
          api
            .deviceAutomationTriggerList(device.id)
            .map(triggers => (device.id, triggers))
        }
        .map(_.toMap.mapFilter(NonEmptyList.fromList))
        .debug("Triggers")
        .toResource

      // (state, services) = (
      //   serializer.read[List[GetStatesData]]("test_state.blob"),
      //   serializer.read[List[ServiceDomain]]("test_services.blob")
      // )
      // _ = {
      //   serializer.write("test_state.blob", state)
      //   serializer.write("test_services.blob", services)
      // }

      codeGenEntities = new CodeGenEntities(state)
      codeGenServices = new CodeGenServices(services)
      _ = fh.util.writeToFile(
        s"$outputDirectory/ha/generated/CodeGenerated.scala",
        codeGenEntities.entitiesCode
      )
      _ = fh.util.writeToFile(
        s"$outputDirectory/ha/generated/CodeGeneratedServices.scala",
        codeGenServices.serviceCode
      )
    } yield ()

  // https://github.com/net-daemon/netdaemon/blob/02e636d7c3dcc60859cbf248cba50c8de87b3dcf/src/HassModel/NetDaemon.HassModel.CodeGenerator/Helpers/EntityIdHelper.cs#L5-L6

  // "roborock", "vacuum", "switch", "light", "unifi"

  //
  // }
  // } // >>      wsService.subscribeStateChanged.use(_.take.debug("take"))
  // service.floors
  //   .debug("output")
}

import Helpers.*
