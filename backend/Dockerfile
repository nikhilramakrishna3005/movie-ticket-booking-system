# Build stage: tests are not run here; they run via ./mvnw verify (integration tests need Docker).
FROM eclipse-temurin:21-jdk AS build
# Without unzip the Maven wrapper downloads the .tar.gz distribution, which fails the .zip
# checksum pinned in maven-wrapper.properties.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/target/movie-booking-backend.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
