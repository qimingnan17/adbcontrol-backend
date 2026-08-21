# 注意：构建前先执行 ./gradlew :backend:installDist 生成 build/install/ 产物

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY backend/build/install/backend/ /app/

ENV PORT=8080
ENV HOST=0.0.0.0

EXPOSE 8080

CMD ["/app/bin/backend"]
