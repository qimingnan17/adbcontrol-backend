# Stage 1: 编译
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /project

# 先复制 Gradle wrapper 和配置文件（利用 Docker 缓存）
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/

# 复制源码
COPY shared/ shared/
COPY backend/ backend/

# 构建 installDist
RUN chmod +x gradlew && ./gradlew :backend:installDist --no-daemon

# Stage 2: 运行
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /project/backend/build/install/backend/ /app/

ENV PORT=8080
ENV HOST=0.0.0.0

EXPOSE 8080

CMD ["/app/bin/backend"]
