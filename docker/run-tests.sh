#!/bin/sh
set -eu

for application in discovery-server api-gateway hotel-service booking-service; do
    echo "Testing ${application}"
    (
        cd "/workspace/${application}"
        sh ./gradlew --no-daemon --max-workers=2 --console=plain build
    )
done
