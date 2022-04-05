#!/bin/bash
./gradlew :app:zip

gsutil cp app/build/distributions/app.zip gs://tsc-artifacts/fetch-gtfs-data/app.zip
