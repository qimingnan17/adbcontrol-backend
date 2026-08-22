# AdbControlApp 部署手册

---

## 第 1 章 环境准备清单

在开始部署之前，请确保以下环境和凭证已准备就绪：

| 项目 | 要求/说明 |
|------|-----------|
| **Node.js** | 18 或更高版本（用于前端构建） |
| **JDK** | 17 或更高版本（用于后端编译） |
| **Docker** | 最新稳定版（用于本地镜像构建和测试） |
| **flyctl** | Fly.io CLI（`brew install flyctl` 或去官网下载） |
| **wrangler** | Cloudflare CLI（可选，前端部署方式 B 会用到，`npm i -g wrangler`） |
| **Cloudflare 账号** | 用于 Pages 托管前端 + R2 对象存储 + DNS 管理 |
| **Fly.io 账号** | 用于托管后端 Ktor 服务 |
| **SQLPub MySQL 凭证** | Host / Port / 数据库名 / 用户名 / 密码 |
| **EMQX Cloud 凭证** | Host / Port / App ID / App Secret / REST Endpoint |
| **Cloudflare R2 凭证** | Endpoint / Bucket 名 / Access Key / Access Secret |

---

## 第 2 章 后端部署到 Fly.io

### 2.1 编译 installDist

首先在项目根目录执行 Gradle 任务，把后端打包成可运行的目录结构：

```bash
./gradlew :backend:installDist
```

**⚠️ 提示**：成功后会生成 `backend/build/install/backend/` 目录，里面包含 `bin/backend` 启动脚本和 `lib/` 依赖 jar 包。Dockerfile 会直接把这个目录拷进镜像。

### 2.2 Dockerfile 构建（本地可选测试）

如果想在本地先跑一遍验证镜像是否正常：

```bash
docker build -t adbcontrol-backend .
docker run -p 8080:8080 adbcontrol-backend
```

然后浏览器访问 `http://localhost:8080/api/health` 看是否返回健康状态。

### 2.3 fly launch / fly deploy

**首次部署**用 `fly launch`，它会根据当前目录的 `fly.toml` 和 `Dockerfile` 创建 app：

```bash
fly launch
```

- 运行过程中会问你 App Name，填一个全局唯一的名字（比如 `adbcontrol-api`），同时它会自动更新 `fly.toml` 里的 `app = "..."` 字段。
- Region 选择 `hkg`（香港），或者直接按回车接受 `fly.toml` 里已有的 primary_region。

**后续部署**直接用：

```bash
fly deploy
```

如果本地构建更稳定（Fly 远程 builder 偶尔抽风），可以加 `--local-only`：

```bash
fly deploy --local-only
```

### 2.4 fly secrets 注入

`fly.toml` 里只写了非敏感的环境变量，真实凭证必须通过 `fly secrets set` 注入（不会写到仓库里）：

```bash
fly secrets set \
  ADB_EMQX_HOST=o8cc1111.ala.cn-hangzhou.emqxsl.cn \
  ADB_EMQX_PORT=8883 \
  ADB_EMQX_APP_ID=o8cc1111 \
  ADB_EMQX_APP_SECRET=EMQX控制台复制 \
  ADB_EMQX_REST_ENDPOINT=https://o8cc1111.ala.cn-hangzhou.emqxsl.cn:8443 \
  ADB_R2_ENDPOINT=https://696e933486bc331658bce6378aaceaea.r2.cloudflarestorage.com \
  ADB_R2_BUCKET=slss-boby \
  ADB_R2_ACCESS_KEY=R2控制台复制 \
  ADB_R2_ACCESS_SECRET=R2控制台复制 \
  ADB_MYSQL_HOST=mysql6.sqlpub.com \
  ADB_MYSQL_PORT=3311 \
  ADB_MYSQL_NAME=slss12 \
  ADB_MYSQL_USER=slss12 \
  ADB_MYSQL_PASSWORD=<从SQLPub控制台复制> \
  ADB_SERVER_URL=https://api.yourdomain.com \
  SESSION_SECRET=$(openssl rand -hex 48)
```

**⚠️ 提示**：`SESSION_SECRET` 一定要用随机生成的 64 字符，不要用固定值，否则 Session 签名有被伪造的风险。

如果需要让 Dashboard/设备详情显示**实时在线状态、电量、命令历史**(即"遥测落库"),
还要额外注入下列两个 secret —— 先去 EMQX 控制台手工建一个专用账号(建议名 `ingestor`),
并给它授予 `status/+ health/+ location/+ activity/+ usage/+ result/+ device/offline/+`
的订阅 ACL,再填进：

```bash
fly secrets set \
  ADB_EMQX_INGEST_USERNAME=ingestor \
  ADB_EMQX_INGEST_PASSWORD=<你刚在 EMQX 控制台设的密码>
```

注入完可以用 `fly secrets list` 检查是否都进去了。

### 2.5 验证健康接口

部署成功后，用 Fly 分配的临时域名（或你自己的域名）验证后端是否存活：

```bash
curl https://your-app-name.fly.dev/api/health
```

正常会返回类似 `{"status":"ok"}` 的 JSON。

### 2.6 自定义域名

1. 在 Cloudflare DNS（或你的 DNS 服务商）添加一条 CNAME 记录：
   - **主机记录**：`api`（即子域名 `api.yourdomain.com`）
   - **值**：`your-app-name.fly.dev`
2. 然后在 Fly 里添加证书：

```bash
fly certs add api.yourdomain.com
```

等几分钟 DNS 生效 + 证书签发完成后，把 `fly.toml` 里的 `ADB_SERVER_URL` 改成 `https://api.yourdomain.com` 再 `fly deploy` 一次。

同时把 `fly secrets set ADB_SERVER_URL=https://api.yourdomain.com` 也同步更新一下（后端代码里可能也读这个）。

---

## 第 3 章 前端部署到 Cloudflare Pages

### 3.1 本地构建前端

进入 `web/` 目录，指定生产环境的 API 基地址，然后 `npm run build`：

```bash
cd web
npm install
VITE_API_BASE=https://api.yourdomain.com npm run build
```

构建产物会生成在 `web/dist/` 目录下。

### 3.2 部署到 Pages

**方式 A：Pages Dashboard 上传 dist 目录（最简单，适合第一次）**

1. 登录 Cloudflare Dashboard → **Workers & Pages** → **Create application** → **Pages** 选项卡 → **Upload assets**
2. Project name 填 `adbcontrol-web`（全局唯一）
3. 把 `web/dist/` 整个目录拖进去，点 Deploy

**方式 B：wrangler CLI 命令行（适合 CI/CD 或脚本化）**

```bash
cd web
npx wrangler pages publish ./dist --project-name=adbcontrol-web
```

首次运行 `wrangler` 会弹出浏览器让你登录 Cloudflare 授权。

### 3.3 Pages 的环境变量配置

登录 Cloudflare Pages → 进入你的项目 → **Settings** → **Environment variables**：

- **Production** 环境下添加：
  - 变量名：`VITE_API_BASE`
  - 变量值：`https://api.yourdomain.com`

**⚠️ 提示**：Pages 的环境变量是**构建时**注入的，改完之后一定要在 **Deployments** 里点 **Retry deployment** 重新构建一次，否则前端代码里读到的还是旧值。

### 3.4 Pages 自定义域名

1. Cloudflare Pages → 项目 → **Custom domains** → **Set up a custom domain**
2. 输入 `web.yourdomain.com`，Cloudflare 会自动帮你在 DNS 里加好 CNAME 记录（如果域名在同一个 CF 账号下）
3. 等证书签发完成（通常 1-2 分钟）

### 3.5 登录页测试

浏览器打开 `https://web.yourdomain.com`（或 Pages 给的 `https://adbcontrol-web.pages.dev`），使用初始管理员账号登录（首次部署时后端自动生成随机密码，见 5.3 节，用 `fly logs` 查看）。

如果能成功进入 Dashboard，说明前后端联调畅通。

---

## 第 4 章 自定义同一主域设置（推荐）

### 为什么要做？

把前端和后端放在**同一个主域名**下（例如都在 `yourdomain.com`，只是子域名不同）有以下好处：

1. **Cookie SameSite 更稳定**：跨主域时 `SameSite=Lax` / `Strict` 可能导致 Session Cookie 丢失，同主域就没问题。
2. **SSO / 未来扩展**：如果以后要做统一登录、WebAuthn 生物识别等，同主域配置简单得多，不容易遇到浏览器安全策略的坑。
3. **CORS 配置更简单**：只需允许一个来源。

### DNS 配置（假设主域是 yourdomain.com）

在 Cloudflare DNS 里添加两条 CNAME：

| 类型 | 主机记录 | 值 | 代理状态 |
|------|---------|-----|---------|
| CNAME | `api` | `your-app-name.fly.dev` | Proxied (橙色云) |
| CNAME | `web` | `adbcontrol-web.pages.dev` | Proxied (橙色云) |

### 后端 CORS 允许前端域名

后端的 CORS 配置通常读环境变量 `CORS_ORIGINS`，用 `fly secrets` 注入进去：

```bash
fly secrets set CORS_ORIGINS=https://web.yourdomain.com
```

如果后端还支持多个来源，用逗号分隔：

```bash
fly secrets set CORS_ORIGINS=https://web.yourdomain.com,https://adbcontrol-web.pages.dev
```

改完之后 `fly deploy`（或者 secrets 变更会自动触发 rolling restart，看 Fly 版本）。

### 最终访问地址

全部配好之后，用户统一访问：

```
https://web.yourdomain.com
```

后端 API 走：

```
https://api.yourdomain.com/api/*
```

---

## 第 5 章 升级流程

### 5.1 后端升级

1. **代码修改并本地测试通过**
2. **重新编译 installDist**：

```bash
./gradlew :backend:installDist
```

3. **重新部署**（推荐本地构建避免远程 builder 问题）：

```bash
fly deploy --local-only
```

4. 验证：

```bash
curl https://api.yourdomain.com/api/health
```

**高级**：如果你用 GitHub Actions，可以配置 push 到 `main` 分支时自动执行 `fly deploy`（Fly 官方有现成的 Action）。

### 5.2 前端升级

1. **代码修改并本地 `npm run dev` 测试通过**
2. **重新构建**（记得带上最新的 API 地址）：

```bash
cd web
npm install
VITE_API_BASE=https://api.yourdomain.com npm run build
```

3. **发布到 Pages**：

```bash
npx wrangler pages publish ./dist --project-name=adbcontrol-web
```

或者如果 Pages 已经连了 Git 仓库，直接 push 到 `main` 就会自动构建部署。

### 5.3 初始密码

首次部署且 `admin_user` 表为空时,后端会自动创建 `admin` 账号并生成**随机初始密码**,只在启动日志中显示一次:

```
初始管理员账号已创建: admin / xxxxxxxxxxxxxxxx (仅显示此一次,请立即登录修改)
```

查看方式(Fly 部署):

```bash
fly logs -a adbcontrol-backend | grep "初始管理员"
```

拿到密码后登录 Web 控制台 → 右上角头像菜单 → **修改密码** 换成自己的强密码。

后端已实现 `POST /api/change-password`(受登录态保护,按用户名限流)。

纯手工 curl 路线:

```bash
# 先登录拿 cookie
curl -c cookiejar -X POST https://api.yourdomain.com/api/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"启动日志里的随机密码"}'

# 用 cookie 改密
curl -b cookiejar -X POST https://api.yourdomain.com/api/change-password \
  -H 'Content-Type: application/json' \
  -d '{"oldPassword":"启动日志里的随机密码","newPassword":"你的新密码至少8位"}'
```

**⚠️ 提示**：如果忘了新密码只能直连 MySQL 重算：`admin_user` 里的 `password_hash` 用 BCrypt，
不要直接把明文写进 SQL；正确做法是起一个本地后端临时调 `PasswordHasher.hash("新密码")` 生成哈希
再 `UPDATE`，或用上面 API 在能登录的前提下自助换掉。
