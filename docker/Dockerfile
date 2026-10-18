# syntax=docker/dockerfile:1
FROM maven:3.9.16-eclipse-temurin-21-noble@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
RUN groupadd --gid 10001 kv && useradd --uid 10001 --gid 10001 --no-create-home kv && mkdir /data && chown kv:kv /data
WORKDIR /app
COPY --from=build /src/control-plane/target/control-plane.jar /src/gateway/target/gateway.jar /src/cli/target/cli.jar /src/demo-backend/target/demo-backend.jar /src/integration-tests/target/integration-tests.jar /app/
USER 10001:10001
ENTRYPOINT ["java"]
CMD ["-jar", "/app/gateway.jar"]
