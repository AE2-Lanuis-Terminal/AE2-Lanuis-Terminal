# AI Agent / 贡献者指南（主仓）

面向自动化助手；完整说明见 [README.md](README.md) 与 [docs/](docs/README.md)。

- Forge 1.20.1 **纯服务端**；仅 `web/` 为 submodule（见 `.gitmodules`）；Client 为独立仓
- **禁止直推 `main`**；版本源是 Web tag；发版见 [docs/RELEASE.md](docs/RELEASE.md)
- 构建：`./gradlew build` 嵌入 `web/dist`；**勿**手拷 dist 进 `src/main/resources/web`
- API：先改 `openapi/openapi.yaml` + [docs/websocket.md](docs/websocket.md)，再改 Java
- 图标：单人 `/ae2lanuis resources render` → `aeKeyResources/`；勿运行时贴图合成
- 注释写意图与边界；配置见 `ae2lanuis-server.toml`（`websocket.port=0` 同 HTTP）
