# syntax=docker/dockerfile:1
# One recipe for every service: docker build --build-arg MODULE=booking-service .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
ARG MODULE
# "locked" serialises parallel compose builds on the shared Maven cache instead of corrupting it.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -q -pl "${MODULE}" -am package -DskipTests \
    && cp "${MODULE}"/target/"${MODULE}"-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
# A fixed numeric user, so Kubernetes can enforce runAsNonRoot.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
