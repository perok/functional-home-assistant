- global system that imports all dashboards?
- should we also do jinja2 as ha templates? https://github.com/HubSpot/jinjava. jinja2 over mustache as that is more familiar to HA users.
  TODO pick up changes in the static sets. Like a new area or a new entity. That must recreate the dump and recreate a dashboard.json
  TODO some popup if not connected
  TODO a json spec step for validating everything?
  TODO switch to handlebars? from mustache, For more custom transformation on static tweaks per instance. Need a cache setup that caches based on the hash of the generated template string or something since we need to compile the template for multiple instances. But share where we can. or jinja2, or the thing from shopify
  TODO a worker for connection to backend api? so we can seamlessly switch between in home connection and a remote connection?
  TODO move from datastar to htmx? Add hyprscript for client side scripting?
  TODO components api amke it clear what is static injected variables, what goes to templating backend rendering and what are values that the client side can script on. SHould JSONata be used to determine values that are injected?
  TODO lots of design stuff, like tabs, are added to themes.libsonnet, should some of that be injectable from the components themselves?
  TODO Parse AST of Pkl directly and use that to validate jsonata. Provide positional errors.
  TODO condital component to filter them out

---
TODO document the API surface
jsonata https://docs.jsonata.org/programming

---

TODO conditions here? for.ex when over X time, dont turn on sofies room light to max? or wrong place?
