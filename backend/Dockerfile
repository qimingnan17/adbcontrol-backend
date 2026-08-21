# syntax=docker/dockerfile:1.6
# ------------------------------------------------------------------
# Stage 1: 用 JDK 17 + Gradle 构建,产出 installDist 目录(含 bin/lib)。
# 注意:secrets.properties 不打到镜像里,生产通过 Fly.io secrets 注入环境变量。
# ------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS build

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip && rm -rf /var/lib/apt/lists/*

# 复用 Gradle / 原生工具缓存层(项目根有 wrapper 优先,没有就降级用已安装的)
WORKDIR /workspace

# 仅复制 Gradle 元数据,尽量命中缓存
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
# shared 是 backend 的依赖,也要先放进来,不然依赖解析会爆
COPY ../shared/src ./shared/src
COPY ../shared/build.gradle.kts ./shared/build.gradle.kts
COPY src ./src
COPY build.gradle.kts ./build.gradle.kts
RUN if [ -f "../build.gradle.kts" ]; then cp ../build.gradle.kts ../settings.gradle.kts ../gradle.properties ./ 2>/dev/null || true; fi

RUN ./gradlew :backend:installDist --no-daemon --no-configuration-cache -x test || \
    ( cd .. && ./gradlew :backend:installDist --no-daemon --no-configuration-cache -x test )

# ------------------------------------------------------------------
# Stage 2: 用 JRE 17 跑,体积比 JDK 小。工作目录 /opt/app,installDist 解在 /opt/app/backend。
# 运行时读取环境变量 ADB_* 作为凭证,secrets.properties 可选(若挂载则优先读取)。
# ------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV APP_HOME=/opt/app
WORKDIR $APP_HOME

COPY --from=build /workspace/backend/build/install/backend $APP_HOME/backend
COPY src/main/resources/logback.xml $APP_HOME/backend/logback.xml
# secrets.properties 模板占位:用户如果要本地跑 docker,可以 -v ./secrets.properties:/opt/app/backend/secrets.properties:ro
COPY secrets.properties $APP_HOME/backend/secrets.properties.template

ENV PORT=8080 \
    HOST=0.0.0.0 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

EXPOSE 8080

CMD ["sh","/opt/app/backend/bin/backend"]
