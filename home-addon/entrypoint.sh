#!/usr/bin/env bash

# turn on bash's job control
set -m

#  https://stackoverflow.com/a/34672970
# https://docs.docker.com/engine/containers/multi-service_container/

sigint_handler()
{
  kill $PID
  exit
}

trap sigint_handler SIGINT

search_directory=folder

while true; do
  latest_file=$(ls -Art "$search_directory" | tail -n 1)
  echo "File found $latest_file"
  if [ "$latest_file" = "" ]; then
    echo "No files found"
    sleep infinity &
  else
    java -jar "$search_directory/$latest_file"  &
  fi
  PID=$!

  # TODO rerun if pid crashes

  # -e modify -e delete -e attrib
  inotifywait -e create -e move -r "`pwd`/$search_directory"
  kill $PID
done

# mv ../modules/home/target/scala-3.6.4/home-assembly-0.1.0-SNAPSHOT.jar folder/