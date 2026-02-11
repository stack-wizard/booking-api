# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build

ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

WORKDIR /app
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy

ENV LANG=C.UTF-8 LC_ALL=C.UTF-8

WORKDIR /app

ARG SPRING_PROFILES_ACTIVE=prod
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}

ARG _JAVA_OPTIONS=""
ENV _JAVA_OPTIONS="${_JAVA_OPTIONS}"

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl ca-certificates \
  && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /certs \
  && curl -sSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
     -o /certs/global-bundle.pem

COPY --from=build /app/target/*.jar /app/app.jar

RUN useradd -r -u 10001 appuser \
  && chown -R appuser:appuser /app /certs

USER appuser

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
