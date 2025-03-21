package fh.codegen

//import scala.meta.* // scalameta for code generation. does not support dotty
import api.homeassistant.HomeAssistantApi
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
      _ <- FHApi
        .from(haServer, haSecret)
        .use(api => program(outputDirectory, api))
    } yield ExitCode.Success

  // TODO Get all sensors

  // TODO code gen
  // TODO create ingtegration: https://developers.home-assistant.io/docs/creating_component_index https://netdaemon.xyz/
  //

  def program(
      outputDirectory: String,
      api: HomeAssistantApi[IO]
  ): IO[Unit] =
    for {
      // _ <- service.postServiceApi("", "", "hello").toResource
      // _ <- hello.testit(service).debug("operatin").toResource
      (state, services) <- (
        api.getStates,
        api.getServices
      ).parTupled

      allEntities <- api.configEntityRegistryList.debug("entities")

      allDevices <- api.configDeviceRegistryList

      allManifests <- api
        .manifestList()
        .map(_.map(m => (m.domain, m)).toMap)
      allConfigEntries <- api
        .configEntriesGet()
        .map(_.map(c => (c.entry_id, c)).toMap)

      allTriggers <- allDevices.values.toSeq
        .parTraverseN(10) { device =>
          api
            .deviceAutomationTriggerList(device.id)
            .map(triggers => (device.id, triggers))
        }
        .map(_.toMap.mapFilter(NonEmptyList.fromList))

      // (state, services) = (
      //   serializer.read[List[GetStatesData]]("test_state.blob"),
      //   serializer.read[List[ServiceDomain]]("test_services.blob")
      // )
      // _ = {
      //   serializer.write("test_state.blob", state)
      //   serializer.write("test_services.blob", services)
      // }

      given AbsolutePosition = AbsolutePosition(
        outputDirectory,
        List("ha", "generated")
      )

      codeGenEntities = new CodeGenEntities(allEntities)
      codeGenDevices = new CodeGenDevices(
        allManifests,
        allConfigEntries,
        allDevices,
        allTriggers,
        codeGenEntities.refererenceOverview
      )

      codeGenServices = new CodeGenServices(services)

      _ = codeGenEntities.refererenceOverview.values.foreach { thing =>
        fh.util.writeToFile(thing.toPath, thing.toCodeFileContent)
      }

      _ = fh.util.writeToFile(
        s"$outputDirectory/ha/generated/CodeGeneratedDevice.scala",
        codeGenDevices.code
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
