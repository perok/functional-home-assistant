# FH Dashboard add-on

A Home Assistant add-on serving the [functional-home-assistant](https://github.com/perok/functional-home-assistant)
Datastar dashboard (`fh-datastar-view`): dashboards authored in
[Pkl](https://pkl-lang.org), rendered server-side, kept live with SSE patches.

- **Ingress**: appears in the HA sidebar, authenticated by HA.
- **User-editable**: dashboards are seeded to the add-on config dir on first
  start and hot-reload on edit. See [DOCS.md](DOCS.md).
- **Image**: `ghcr.io/perok/fh-dashboard` (amd64 + aarch64), built by GitHub
  Actions from [home-addon/Dockerfile](Dockerfile). Releasing = merging a
  `version:` bump in [config.yaml](config.yaml) to main; the workflow builds,
  publishes, and creates the matching `vX.Y.Z` tag.
