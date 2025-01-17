$version: "2.0"

// todo ha.low-level-api
namespace perok.ha

// https://www.home-assistant.io/docs/configuration/templating/
// to_json ..
// https://github.com/disneystreaming/smithy4s/discussions/954
@http(method: "POST", uri: "/api/template", code: 200)
operation Template {
    input: TemplateInput
    output: TemplateOutput
}

structure TemplateInput {
    @required
    template: String
}

structure TemplateOutput {
    @required
    @httpPayload
    output: Document
}

