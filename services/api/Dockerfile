FROM eclipse-temurin:25-jdk-jammy AS builder

WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY build.gradle settings.gradle .
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true
COPY src src
RUN ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:25-jre-jammy

WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
