# Changelog

本仓库遵循 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [SemVer](https://semver.org/)。

发版约定见 [docs/RELEASE.md](docs/RELEASE.md)。日常 PR 只改 **Unreleased**；发行 PR 将条目收入 `## [X.Y.Z] - YYYY-MM-DD`。

## [Unreleased]

### Changed

- 仅保留 `web/` submodule；Client 改为独立仓，不再作为本仓 submodule

### Added

- 发布流程文档与 CI/Release workflow 脚手架（默认不自动推送 GitHub Release / Modrinth）
- 根目录 MIT `LICENSE`（与 `mod_license` 对齐）

## [0.1.0] - 2026-08-14

### Added

- 初始版本：Forge 1.20.1 纯服务端模组、嵌入 Web UI、HTTP/WebSocket API
