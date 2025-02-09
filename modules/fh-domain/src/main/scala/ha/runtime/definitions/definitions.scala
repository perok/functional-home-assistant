package ha.runtime.definitions

//trait StaticDevice {
//  def _device: Device
//  def id: String
//  def name: String
//  def areaId: Option[String]
//}

case class Thing( // TODO Entity
    id: String,
    device_class: Option[String],
    integration: Option[String],
    supportedFeatures: List[Int]
)

trait Service {
  // def domain: String
  // def serviceId: String
}
