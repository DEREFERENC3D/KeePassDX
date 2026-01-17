#!/bin/bash
podman run --rm -it -v "$(pwd):/project:Z" docker.io/mobiledevops/android-sdk-image:34.0.1 sh -c 'cd /project && ./gradlew build -x lint -x lintVitalFreeRelease -x lintVitalLibreRelease'
