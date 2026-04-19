FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8081
ENTRYPOINT ["java", \
  "-Xms256m", "-Xmx512m", \
  "-XX:+UseZGC", \
  "-XX:+UseStringDeduplication", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
