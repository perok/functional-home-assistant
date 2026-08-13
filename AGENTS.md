# Scala projects

* ALWAYS use Metals MCP tools to compile and run tests instead of relying on bash commands
* If MCP tools are not available report that to the user
* after adding a dependency to `build.sbt`, ALWAYS run the `import-build` tool
* to lookup a dependency or the latest version, use the `find-dep` tool
* to lookup the API of a class, use the `inspect` tool
* NEVER use non-local returns
