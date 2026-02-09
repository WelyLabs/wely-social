# Stage 1: Build
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

# Copy maven wrapper and pom
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

# Copy source and build
COPY src src
RUN ./mvnw package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Copy built JAR
COPY --from=build /app/target-maven/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
