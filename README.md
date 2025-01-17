## Inspiration

https://github.com/hassio-addons/addon-appdaemon/blob/edc32b49dba7e0757e95916ea672543b821d147b/appdaemon/config.yaml#L20-L25

## Updates

```mermaid

flowchart TD
    ghrepo>Gh repo]
    ghactiondeploy>GH Action Deploy in HA]
    ghactionchange>GH Action HA change]
    ha>HomeAssistant server]
    ghrepo -- master --> ghactiondeploy
    ghrepo -- Pull request --> ghactiondeploy
    ghactiondeploy --> ha
    ha -- Change event webhook trigger --> ghactionchange
    ghactionchange -- Create PR --> ghrepo

```
Triggers from these events to webhooks to github to rebuild
device_registry_updated (5 listeners)
entity_registry_updated (10 listeners)

## Entity handling

https://developers.home-assistant.io/docs/core/entity/light/
1. api generates defintioins
2. Pga. for.eks at en entity har effect som er hva som er nå og effekt_list som er hva som er støtta så blir det 
   veldig vanskelig å lage ett godt api generisk. Derfor burde det være at noen entitettyper har overstyrende
   custom greier som bedre støtter dette (effect: "effekt1" | "effekt2")

## Codegen

https://stackoverflow.com/questions/11509843/sbt-generate-code-using-project-defined-generator
https://www.scala-sbt.org/1.x/docs/Howto-Generating-Files.html

