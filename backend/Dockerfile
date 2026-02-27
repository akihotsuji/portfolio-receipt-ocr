# ===== Build Stage =====
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Maven Wrapper
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# 依存関係をキャッシュ（ソースコード変更時の再ビルドを高速化）
RUN ./mvnw dependency:go-offline -B

# ソースコードをコピーしてビルド
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ===== Runtime Stage =====
FROM eclipse-temurin:21-jre-alpine

# セキュリティ: 非rootユーザーで実行
RUN addgroup -g 1001 appgroup && \
    adduser -u 1001 -G appgroup -D appuser

WORKDIR /app

# ビルド成果物をコピー
COPY --from=builder /app/target/*.jar app.jar

# 非rootユーザーに切り替え
USER appuser

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
