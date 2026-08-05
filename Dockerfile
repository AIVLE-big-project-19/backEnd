# --- Build stage ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- Runtime stage ---
FROM eclipse-temurin:17-jre-jammy AS runtime
ENV TZ=Asia/Seoul
RUN useradd --system --uid 1000 spring
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
