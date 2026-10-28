# The Dockerfile's build stage without the build: what scripts/test.sh runs
# Gradle in. getAndroid.sh needs curl and zip.
FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl zip unzip && rm -rf /var/lib/apt/lists/*
