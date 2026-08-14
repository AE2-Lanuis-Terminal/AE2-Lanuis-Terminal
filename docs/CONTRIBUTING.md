# 贡献指南

## 分支

- **禁止直推 `main`**，只接受 Pull Request / Merge Request
- 通网后请按 [BRANCH_PROTECTION.md](BRANCH_PROTECTION.md) 打开 GitHub 分支保护

## Changelog

- 日常 PR：只更新根目录 `CHANGELOG.md` 的 `## [Unreleased]`
- **发行 PR**：将 Unreleased 收成 `## [X.Y.Z] - YYYY-MM-DD`，并 bump `gradle.properties` 的 `mod_version`；同时把 `web` submodule 钉到对应 **Web tag**

完整发版顺序（Web → 主仓 → Client）见 [RELEASE.md](RELEASE.md)。

## 代码习惯

- 注释写意图、边界、平台坑；避免灌水
- 不要手拷 `web/dist` 进 `src/main/resources/web`
- API 变更流程见 [DEVELOPMENT.md](DEVELOPMENT.md)

## 文档索引

| 文档 | 用途 |
|------|------|
| [INSTALL.md](INSTALL.md) | 安装与运维 |
| [DEVELOPMENT.md](DEVELOPMENT.md) | 本地构建 |
| [RELEASE.md](RELEASE.md) | 发布流水线 |
| [websocket.md](websocket.md) | WS 协议 |
