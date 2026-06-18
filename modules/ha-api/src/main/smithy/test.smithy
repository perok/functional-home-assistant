$version: "2.0"

namespace perok.ha

use alloy#jsonUnknown
use alloy#simpleRestJson
use alloy#untagged

@httpBearerAuth
@simpleRestJson
service HomeAssistantApiService {
    version: "1.0.0"
    operations: [
        Config
        GetServicesApi
        // Calls a service within a specific domain. Will return when the service has been executed.
        PostServiceApi // CallService Post /api/services/{domain}/{service}
        GetStates
        GetEntityStateApi
        // SetEntityState Post /api/states/{entity_id}
        // FireEvent Post /api/events/{event_type}
        Template
    ]
}

@readonly
@http(method: "GET", uri: "/api/states", code: 200)
operation GetStates {
    output: GetStatesOutput
}

structure GetStatesOutput {
    @required
    @httpPayload
    output: GetStatesDataList
}

list GetStatesDataList {
    member: GetStatesData
}

string EntityId

structure GetStatesData {
    @required
    entity_id: EntityId

    last_reported: String

    last_updated: String

    @required
    state: Document

    last_changed: String

    context: Document

    @required
    attributes: GetStatesDataAttributes

    @jsonUnknown
    unknown: UnknownProperties
}

structure GetStatesDataAttributes {
    id: String

    last_triggered: String

    mode: Document

    current: Document

    // Binary of supported things
    supported_features: Integer

    friendly_name: String

    device_class: String

    integration: String

    @jsonUnknown
    unknown: UnknownProperties
}

@http(method: "GET", uri: "/api/states/{entity_id}", code: 200)
operation GetEntityStateApi {
    input: StateInput
    output: Greeting
}

structure StateInput {
    @httpLabel
    @required
    entity_id: String
}

structure Greeting {
    @required
    message: String
}

@http(method: "POST", uri: "/api/services/{domain}/{service}", code: 200)
operation PostServiceApi {
    input: PostServiceApiInput
    output: PostServiceApiOutput
}

structure PostServiceApiInput {

    @httpLabel
    @required
    domain: String

    @httpLabel
    @required
    service: String

    // https://developers.home-assistant.io/docs/documenting/yaml-style-guide?_highlight=area_id#service-action-targets
    entity_id: String
    area_id: String
    device_id: String
}

structure PostServiceApiOutput   {
    @required
    @httpPayload
    output: PostServiceApiListData
}

list PostServiceApiListData  {
    member: PostServiceApiOutputData
}

structure PostServiceApiOutputData {
    @jsonUnknown
    unknown: UnknownProperties
}


@readonly
@http(method: "GET", uri: "/api/services", code: 200)
operation GetServicesApi {
    output: ServicesApiOutput
}

structure ServicesApiOutput {
    @required
    @httpPayload
    output: ServicesData
}

list ServicesData {
    member: ServiceDomain
}

structure ServiceDomain {
    @required
    domain: String

    @required
    services: ServiceOperations
}

map ServiceOperations {
    key: String
    value: Service
}

structure Service {
    //@required
    name: String

    description: String

    //@required
    //fields: ServiceFields

    //  target: ServiceTarget
    target: ServiceTargetEntity

    response: ServiceResponse

    @jsonUnknown
    unknown: UnknownProperties
}

structure ServiceResponse {
    entity: ServiceTargetEntities
}

structure ServiceTarget {
    entity: ServiceTargetEntities
}

list ServiceTargetEntities {
    member: ServiceTargetEntity
}

structure ServiceTargetEntity {
    domain: ServiceTargetEntityDomains

    entity: ServiceTargetEntityEntities

    supported_features: ServiceTargetEntityFeatures

    integration: String

    @jsonUnknown
    unknown: UnknownProperties
}

list ServiceTargetEntityEntities {
    member: ServiceTargetEntityEntity
}

structure ServiceTargetEntityEntity {
    domain: ServiceTargetEntityEntityDomains
}

list ServiceTargetEntityEntityDomains {
    member: String
}

list ServiceTargetEntityFeatures {
    member: Integer
}

list ServiceTargetEntityDomains {
    member: ServiceTargetEntityDomain
}

@untagged
union ServiceTargetEntityDomain {
    services: String
    unknown: UnknownProperties
}

map ServiceFields {
    key: String
    value: ServiceField
}

structure ServiceField {
    default: ServiceFieldDefault

    name: String

    description: String

    required: Boolean

    selector: ServiceFieldSelector

    example: Document

    filter: ServiceFieldFilter

    advanced: Boolean

    //fields: Document

    // collpased: Boolean
    // fields: map?
    @jsonUnknown
    unknown: UnknownProperties
}

structure ServiceFieldFilter {
    supported_features: ServiceTargetEntityFeatures

    attribute: Document

    @jsonUnknown
    unknown: UnknownProperties
}

@untagged
union ServiceFieldDefault {
    boolean: Boolean
    number: Integer
    unknown: Document
}

structure ServiceFieldSelector {
//    boolean: Boolean

    text: ServiceFieldSelectorText

    number: ServiceFieldSelectorNumber

    entity: ServiceFieldSelectorEntity

    select: ServiceFieldSelectorSelect

    device: ServiceFieldSelectorDevice

    @jsonUnknown
    unknown: UnknownProperties
}

structure ServiceFieldSelectorDevice {
    integration: String

    @jsonUnknown
    unknown: UnknownProperties
}

structure ServiceFieldSelectorSelect {
    options: ServiceFieldSelectorSelectOptions
}

list ServiceFieldSelectorSelectOptions {
    member: Document
}

structure ServiceFieldSelectorSelectOptionsEntity {
    @jsonUnknown
    unknown: UnknownProperties
}

structure ServiceFieldSelectorEntity {
    //domain: String

    @jsonUnknown
    unknown: UnknownProperties
}

@untagged
union ServiceFieldSelectorText {
    string: String
    unknown: Document
}

@untagged
union ServiceFieldSelectorNumber {
    number: Integer
    bounds: ServiceFieldSelectorNumberBounds
    unknown: Document
}

structure ServiceFieldSelectorNumberBounds {
    min: Double
    max: Double
    step: Double
    unit_of_measurement: String
}

@readonly
@http(method: "GET", uri: "/api/config", code: 200)
operation Config {
    output: ConfigData
}

structure ConfigData {
    @jsonUnknown
    unknown: UnknownProperties
}

map UnknownProperties {
    key: String
    value: Document
}

