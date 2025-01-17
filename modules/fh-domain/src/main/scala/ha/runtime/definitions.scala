package ha.runtime

object definitions {
  case class Thing(
      id: String,
      device_class: Option[String],
      integration: Option[String],
      supportedFeatures: List[Int]
  )

  trait Service {
    //def domain: String
    //def serviceId: String
  }
}
