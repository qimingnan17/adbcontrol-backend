# adbcontrol-backend

AdbControlApp 的后端服务（Ktor + Kotlin/JVM）。负责配对签发、EMQX ACL 代理、更新分发、健康检查，并归档遥测数据至远程 MySQL。

> 这是 **AdbControlApp**（基于 MQTT + Shizuku 的 Android 设备管理 Agent 平台）拆分出的 4 个仓库之一。完整架构设计见本仓库的 [DESIGN.md](DESIGN.md)。

## 相关仓库

| 仓库 | 说明 |
| --- | --- |
| [adbcontrol-backend](https://github.com/qimingnan17/adbcontrol-backend) | 后端服务（本仓库） |
| [adbcontrol-controller](https://github.com/qimingnan17/adbcontrol-controller) | 主控端 Android App |
| [adbcontrol-controlled](https://github.com/qimingnan17/adbcontrol-controlled) | 被控端 Android App |
| [adbcontrol-web](https://github.com/qimingnan17/adbcontrol-web) | Web 管理端 |

## 模块

- `backend/` — Ktor 服务主体（路由、服务、安全、数据库）
- `shared/` — 跨端共享的协议与数据模型（`com.adbcontrol.shared`）

## 构建与运行

```bash
./gradlew :backend:run
```

生成可分发产物（Docker 镜像使用）：

```bash
./gradlew :backend:installDist
```

## 部署

- 运行文档：[docs/RUN.md](docs/RUN.md)
- 部署文档：[docs/DEPLOY.md](docs/DEPLOY.md)
- 容器：见 [Dockerfile](Dockerfile)；Fly.io 配置见 [fly.toml](fly.toml)

## 配置

复制 `secrets.properties.template` 为 `secrets.properties` 并填入 EMQX / R2 / MySQL 凭证。凭证文件不会进入 git（见 `.gitignore`）。
