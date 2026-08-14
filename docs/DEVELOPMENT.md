# 本地开发（主仓）

面向要改模组逻辑或联调嵌入 UI 的开发者。服主装服请看 [INSTALL.md](INSTALL.md)。

## 环境

- JDK **17+**
- Node **24+**（`:web:npmBuild`）
- Git submodule：克隆时加 `--recurse-submodules`，或之后执行  
  `git submodule update --init --recursive`

本地也可用 Windows Junction 指向已 clone 的 Web 仓，但 **CI 与正式贡献以 [.gitmodules](../.gitmodules) 为准**。Client 为独立仓，不作为本仓 submodule。

## 构建

```bash
./gradlew build                 # 跑 :web:npmBuild，嵌入 web/dist → jar 内 /web
./gradlew build -PskipWebBuild=true   # 跳过前端构建（仍需已有 web/dist）
```

- **禁止**把手拷的 `dist` 放进 `src/main/resources/web`
- 分发只用 `build/libs/ae2lanuis-*.jar`，不要用 `-thin`

改 UI：在 [Web 仓](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal-Web) 开发并发版 tag，再更新本仓 `web` submodule 指针。

## API 变更顺序

1. 改 [openapi/openapi.yaml](../openapi/openapi.yaml) 与 [websocket.md](websocket.md)
2. 改 Java 实现
3. 通知 Web 仓 `npm run gen:api` 更新类型

## 配置与行为备忘

- 配置文件：`config/ae2lanuis-server.toml`
- `websocket.port` 默认 `0` = 与 HTTP 同端口；显式端口则独立监听
- `websocket.pushIntervalMs` 仅服务端可调
- `auth.adminPermissionLevel` 控制 Web 管理 API（默认 2）
- 图标：单人 `/ae2lanuis resources render` → `aeKeyResources/`；勿做运行时贴图合成 / CDN

## 相关文档

- [INSTALL.md](INSTALL.md) — 命令与绑定步骤
- [RELEASE.md](RELEASE.md) — 发版
- [CONTRIBUTING.md](CONTRIBUTING.md) — PR 约定
- [AGENTS.md](../AGENTS.md) — 给 AI / 自动化助手的短约定
