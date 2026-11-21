# Build the fat jar first, then run it on a JRE. No build tools in the runtime image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/payment-notification-intake.jar app.jar

# A fixed, unprivileged user: nothing in the container needs root, and the image should not be able to
# write to its own filesystem.
RUN useradd --system --uid 10001 --create-home intake
USER 10001

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
