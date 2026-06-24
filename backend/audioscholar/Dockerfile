# ===================================================================
# Stage 1: Build using Maven + Java 21 LTS
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy POM and download dependencies (cacheable layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B -Ddependency-check.skip=true

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -B -Ddependency-check.skip=true

# Stage 2: Run using lightweight Java 21 LTS JRE (Alpine for optimization)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install font libraries (Required for Apache POI/PDFBox) - Alpine equivalent
RUN apk add --no-cache fontconfig ttf-dejavu ffmpeg

# Copy the built application
COPY --from=builder /app/target/audioscholar-*.jar app.jar

<<<<<<< HEAD
# Optimized ENTRYPOINT with Java 24 Compact Object Headers (Single Line)
ENTRYPOINT ["java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCompactObjectHeaders", "-XX:+UseSerialGC", "-Xss256k", "-XX:MaxRAMPercentage=75.0", "-XX:ActiveProcessorCount=2", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
=======
# Runtime tuned for constrained containers without experimental JVM flags
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xss256k", "-XX:MaxRAMPercentage=75.0", "-XX:ActiveProcessorCount=2", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
>>>>>>> a991831ed45f0ec696832d73e09c89256a68cadd
