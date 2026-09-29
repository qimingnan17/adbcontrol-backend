# 系统设置页重构 — 更新说明（2026-09）

> 对应原型：`docs/ui/06-settings-restructure.html`
> 涉及仓库：`adbcontrol-web`（前端主改）、`adbcontrol-backend`（后端补齐依赖接口 + 内置静态包）

---

## 一、背景与目标

原设置页把「按技术栈堆叠的字段表单」和「一堆快捷按钮」混在一起，存在若干功能性问题：

1. **保存 / 生效语义混乱**：顶部「保存配置」写 `secrets.properties`，但 R2 / EMQX / DB 改完必须重启才生效且无提示；CF 的 OIDC 走独立接口、D1/隧道/一键绑定点了直接落库，同一页三种生效方式；「测试」用的是输入框当前值而非已保存值。
2. **对外域名有 5 个入口**（修改域名 / 设为对外 URL / 穿透绑定 / serverUrl 输入框 / 手动绑定弹窗），状态互不同步，且唯一正确的完整流程入口最隐蔽。
3. **公网权限态处理粗糙**：客户端只看 `isTailscale` 就 `return`，导致公网 + 管理员会话下页面变成空白表单。
4. **进入 CF 页默认无数据**，必须手动点同步。
5. **重复字段**跨 tab / 弹窗。
6. **校验按"看起来对"写**，对外地址允许内网 / 环回地址。
7. **没有整体状态**，用户需跨 3 个 tab 拼图，且看板会把"填了 bucket 但没 AK/SK"误判为已绑定。

本次重构按**功能域**重排信息架构，取消独立的「CF 资源中心」，并逐条修复上述问题。

---

## 二、信息架构变更

页签由「R2 / EMQX / 服务与网络 / 数据库 / CF 资源中心」改为：

| 新页签 | 内容 | 来源 |
| --- | --- | --- |
| **总览**（新增） | 6 张配置状态卡，一键跳转 | 新 |
| **隧道与域名** | 对外服务地址卡 + 托管域名 + 高级（CF Token / 隧道列表） | 服务与网络 + CF 资源中心 |
| **存储 R2** | R2 凭据 + 连接测试 + 只读桶发现 + 逐桶绑定 | R2 页 + CF 资源中心 |
| **消息 EMQX** | EMQX 凭据 + 连接测试 + Ingestor | EMQX 页 |
| **数据库** | MariaDB 主库 + D1 备用（逐库手动绑定） | 数据库页 + CF 资源中心 |
| **登录与安全** | OIDC 登录 + 访问控制 + 发布令牌 | CF 资源中心 + 服务与网络 |

**取消**独立「CF 资源中心」页签：其能力按目的地下沉（隧道/域名→隧道与域名、R2 桶→存储 R2、D1→数据库、Token→隧道与域名→高级）。

---

## 三、前端改动（adbcontrol-web）

### 3.1 `src/views/Settings.vue`（重写）

- **访问门取代空白表单**：不再用 `isTailscale` 提前 `return`；改为**依据服务端响应判断**——`loadAll()` 收到 HTTP 403 时展示「此页仅限内网 / Tailscale 或管理员会话访问」的门页，并提供「重新检测」。这同时修正了「公网 + 管理员会话本可访问却被前端拦掉」的问题。
- **保存语义统一**：
  - 每张配置卡独立「保存」，走 `POST /api/admin/settings/secrets`（后端为**合并语义**，仅提交本卡字段）。
  - 卡片右上角标注 `改动需重启生效` / `即时生效`。
  - 顶部只保留「保存全部并重启服务」（保存全部字段 + 重启后端）。
  - 测试按钮旁注明「测试使用当前输入值；通过后仍需点保存」。
- **对外域名单一入口**：「对外服务地址」卡片（更换地址 / 添加主机名 / 换用新隧道），移除错误的 `trycloudflare` 默认值；隧道列表降为只读并移入「高级」折叠区。
- **按功能校验**：更换地址弹窗实时拒绝内网 / 环回 / 保留地址（`localhost`、`10.`、`192.168.`、`172.16-31.`、`100.64-127.`、`127.`），并提示「手机需能访问」。
- **手动绑定**：删除「一键全量自动绑定」与「手动配置绑定」弹窗；R2 / D1 改为只读发现 + 逐项显式绑定，避免自动绑错资源。
- **总览诚实化**：明示「配置状态（非实时探活）」；R2 在缺 AK/SK 时显示 `部分配置` 而非已绑定。
- **自动同步**：进入页面在已保存 Token 时自动 `sync` 一次，资源默认可见。
- **页面刷新时** 支持 `?tab=` 旧值映射（`r2→storage`、`emqx→mqtt`、`server/cf→tunnel`、`db→database`）。

### 3.2 `src/api/cloudflare.js`

- `provisionCfTunnel(...)` 新增 `enableMqttWss` 参数。
- 新增 `createCfTunnel({ name, apiToken })`。

---

## 四、后端改动（adbcontrol-backend）

### 4.1 `service/CloudflareService.kt`

- **`putTunnelIngress` 改为合并语义**：先 `GET` 现有 ingress，过滤掉旧兜底规则与本次要写的同主机名 / `/mqtt` 规则，再追加，最后补 `http_status:404` 兜底。**不再整份覆盖**，因此「添加主机名」不会冲掉其他主机名。
- 新增 `mqttWssHost` 可选参数：非空时追加入口规则 `{ path: "/mqtt", service: "https://<host>:8084" }`，为受控端提供 MQTT over WSS 双栈入口。
- 新增私有辅助 `fetchTunnelIngress(...)`。

### 4.2 `model/CloudflareModels.kt`

- `CfProvisionTunnelRequest` 新增 `enableMqttWss: Boolean = false`。
- 新增 `CfCreateTunnelRequest` / `CfCreateTunnelResponse`。

### 4.3 `route/SettingsRoutes.kt`

- `POST /cloudflare/provision-tunnel`：读取 `enableMqttWss`，EMQX 主机取自设置项 `emqx.host`，传给 `putTunnelIngress`，并追加 `enable_mqtt_wss` 步骤回报。
- **新增 `POST /cloudflare/create-tunnel`**：仅创建远程托管隧道（不写入口规则），校验名称与 Token，复用 `cfService.createTunnel`。

### 4.4 内置静态资源

`adbcontrol-web/dist/` 已重新构建并同步至 `backend/src/main/resources/static/`（40 个资源文件 + `index.html`）。

### 4.5 前端产物自动同步（`syncWebDist`）

为替代此前每次手动执行的 `cp -r adbcontrol-web/dist/* .../static/`，在 `backend/build.gradle.kts` 中新增 Gradle 任务 `syncWebDist`（`Sync` 类型），并挂到 `processResources` 之前：

```bash
# 本地开发：构建前端后，直接构建/运行后端即可，产物会自动同步进 static
cd adbcontrol-web && npm run build
cd ../adbcontrol-backend && ./gradlew :backend:run     # 或 :backend:distZip / :backend:jar

# 也可单独手动触发
./gradlew :backend:syncWebDist
```

行为说明：

- 源目录 `../adbcontrol-web/dist`（相对后端仓库根）存在 `index.html` 时才执行；
- 采用 `Sync` 镜像语义，会删除 `static` 中不属于 dist 的旧哈希文件，避免残留；
- **CI / Fly 场景**仅 checkout 后端仓库、没有前端源码，任务自动跳过，回退使用仓库中已提交的 `src/main/resources/static`；
- 同步后 `static` 会出现改动，照常提交后端仓库即可（保持 CI 兜底的那份产物不过期）。

---

## 五、接口变更清单

| 方法 | 路径 | 变更 |
| --- | --- | --- |
| POST | `/api/admin/settings/cloudflare/provision-tunnel` | 请求体新增 `enableMqttWss?: boolean`；ingress 改为合并写入 |
| POST | `/api/admin/settings/cloudflare/create-tunnel` | **新增**：`{ name: string, apiToken?: string }` → `{ ok, tunnelId?, tunnelName?, message }` |

其余接口签名不变。`sync` 在 `apiToken` 为空或含掩码时回退读取已保存 Token（既有行为，前端据此在同步时对掩码 Token 传空串）。

---

## 六、本地验证结果

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 后端编译 | `./gradlew :backend:compileKotlin` | ✅ 通过 |
| 后端单测 | `./gradlew :backend:test` | ✅ BUILD SUCCESSFUL |
| 前端构建 | `npm run build` | ✅ built in ~12s（`Settings-*.js` 47 kB） |
| 静态同步 | `dist/` → `static/` | ✅ 39 → 40 个 assets |

> 说明：未做真机 / 浏览器端到端联调（需后端服务与 Cloudflare / EMQX 凭据）。建议部署后在 Tailscale 内网打开设置页，逐 tab 走查一遍：总览状态 → 隧道与域名（更换地址校验、添加主机名、新建隧道）→ 存储 R2（测试 + 逐桶绑定）→ 数据库（D1 逐库绑定）→ 登录与安全。

---

## 七、已知限制 / 待办

1. **子域名占用预检**：原型中的实时占用提示未落生产——后端目前没有查询 DNS 记录的只读接口，占用冲突仍由提交后返回的错误信息提示。
2. **隧道已绑主机名列表**：`CfTunnel` 模型暂无 `hostnames` 字段，「对外服务地址」卡未展示全部主机名；如需展示需后端补接口。
3. **总览为配置状态**：非实时探活（EMQX / DB 无健康检查接口）；如需真实健康度需后端新增探活接口。
4. **MQTT 双通道自动优选（受控端）**：本次仅落地隧道侧 `/mqtt` 入口；受控端 `MqttManager` 的双路探测 / 自动切换为独立工作项（原型勾选项对应后端已就绪，受控端待做）。
