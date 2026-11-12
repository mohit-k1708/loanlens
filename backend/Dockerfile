# LoanLens backend image (used by Railway). Build context = this folder (backend/).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/loanlens-backend-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 loanlens
COPY --from=build /build/app.jar app.jar
USER loanlens
# Railway injects PORT; the app reads it (server.port=${PORT:8080}). Memory kept small for a free/trial instance.
ENV JAVA_TOOL_OPTIONS="-Xmx400m -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
