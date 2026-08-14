# 安装说明（模组）

## 服务端模组

1. 克隆主仓（含 submodule）：`git clone --recurse-submodules …`
2. 构建：`.\gradlew.bat build`（自动 `:web:npmBuild` 并嵌入 UI）
3. 将 `build/libs/ae2lanuis-*.jar` 放入 **服务端** `mods/`（需 Applied Energistics 2）
4. 启动后检查日志：`AE2 Lanuis HTTP listening on ...` 与 `AE2 Lanuis WebSocket listening on ...`
5. 配置：`config/ae2lanuis-server.toml`（HTTP 默认 `8765`；WebSocket 默认与 HTTP 同端口，`websocket.port=0`；设为其它值如 `8766` 则独立监听）
   - Docker：设置 `http.publicHost` 或环境变量 `AE2LANUIS_PUBLIC_HOST`
6. **物品图标（必做一次）**：联机玩家**不需要**装本模组；图标由管理员预烘焙：
   1. 用与服务器相同的模组列表开**单人世界**
   2. 执行 `/ae2lanuis resources render`（可选 `limit` 每帧张数、`size` 边长，默认 128）
   3. 将游戏根目录下的 `aeKeyResources/` 上传到专用服游戏根目录（与 `config/` 同级）
   4. 配置项 `http.iconResourcesDir` 默认为 `aeKeyResources`
   5. 其它：`resources status|count|cancel|clear`；`/ae2lanuis help` 查看全部命令

## 常用命令

| 命令 | 说明 |
|------|------|
| `/ae2lanuis help` | 帮助 |
| `/ae2lanuis password <密码>` | 设密并绑定 ME 网络 |
| `/ae2lanuis password clear` | 清除本人绑定 |
| `/ae2lanuis status` | 本人绑定与网页地址 |
| `/ae2lanuis http` | HTTP / WS 监听状态（WS 默认同 HTTP 端口） |
| `/ae2lanuis bindings` | 列出全部绑定（权限 ≥2） |
| Web「管理端」 | OP 登录后弹框选择；独立控制台查看全部网络与审计日志；在线网络可进入用户端 |
| `/ae2lanuis resources render [limit] [size]` | 单人烘焙图标 |
| `/ae2lanuis resources cancel` | 取消烘焙（单人） |
| `/ae2lanuis resources status\|count` | 资源目录与 PNG 张数 |
| `/ae2lanuis resources clear` | 清空 PNG（权限 ≥2；单人客户端亦可） |

## 绑定

1. 无线终端链接到目标 ME 网络
2. 持有该终端：`/ae2lanuis password <密码>`
3. 之后可用浏览器（嵌入 UI / 独立 Web）或 Client 登录

## 独立 Web / Client

见各子仓 README。Web 开发需可达模组 HTTP（可用 `VITE_API_BASE_URL`）。本地改模组见 [DEVELOPMENT.md](DEVELOPMENT.md)。
