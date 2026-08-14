# 三仓发布流程（总册）

组织：[AE2-Lanuis-Terminal](https://github.com/AE2-Lanuis-Terminal)

| 仓 | 产物 | 版本关系 |
|----|------|----------|
| [Web](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal-Web) | 源码 + `vX.Y.Z` **tag（版本源）** | 先发 |
| [主仓](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal) | Forge jar（嵌入 Web `dist`） | 钉 Web tag 后发 |
| [Client](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal-Client) | Windows NSIS + Android APK | 检出 Web tag 后发 |

当前若无法连接 GitHub：**流程与 workflow 脚手架已齐**；自动创建 Release / 上传 Modrinth 步骤默认关闭（`PUBLISH=false`），通网后按下方检查表打开。

## 分支策略

- 三仓均 **禁止直推 `main`**，只接受 Pull Request / Merge Request。
- 合入 `main` 的日常 PR：只写 `CHANGELOG.md` 的 `## [Unreleased]`。
- **发行 PR**：把 Unreleased 收成 `## [X.Y.Z] - YYYY-MM-DD`，并 bump 版本字段；CI 检测到该 diff 后走 release 流水线。

检测脚本：[scripts/is-release-changelog.sh](../scripts/is-release-changelog.sh)（相对 `origin/main` 的 `CHANGELOG.md` diff 是否新增 `## [X.Y.Z]`）。

## 发版顺序

```text
1. Web 发行 PR → 合入 main →（通网后）打 tag vX.Y.Z + GitHub Release
2. 主仓发行 PR：submodule web 钉到 vX.Y.Z，bump mod_version，写 CHANGELOG
   → 合入 main → 构建 jar →（通网后）GH Release + Modrinth
3. Client 发行 PR：声明依赖 Web vX.Y.Z，bump 三处 version，写 CHANGELOG
   → 合入 main → 构建 NSIS + APK →（通网后）GH Release
```

主仓与 Client **必须消费已存在的 Web tag**，不要并行抢发同一版本号却指向不同 Web 提交。

## Changelog 约定（Keep a Changelog）

三仓根目录均有 `CHANGELOG.md`：

```markdown
## [Unreleased]

### Added
- …

## [0.1.0] - 2026-08-14

### Added
- 初始版本
```

发行 PR 必改版本字段：

| 仓 | 字段 |
|----|------|
| Web | `package.json` → `version` |
| 主仓 | `gradle.properties` → `mod_version`；`web` submodule → Web `vX.Y.Z` |
| Client | `package.json`、`src-tauri/Cargo.toml`、`src-tauri/tauri.conf.json` |

## 仓间依赖

- 主仓：`web/`、`client/` 为 **git submodule**（见 [.gitmodules](../.gitmodules)）。克隆：`git clone --recurse-submodules …`
- Client 独立开发：兄弟目录名为 `web`，或运行 `scripts/link-web.ps1` 指向 Web 仓；CI 会额外 checkout Web 到 `../web`。
- **不要**把手拷 `web/dist` 进主仓 `src/main/resources/web`。

## Workflows

| 仓 | CI | Release |
|----|----|---------|
| Web | `.github/workflows/ci.yml` | `.github/workflows/release.yml` |
| 主仓 | `.github/workflows/build.yml` | `.github/workflows/release.yml` |
| Client | `.github/workflows/ci.yml` | `.github/workflows/release.yml` |

Release job 环境变量：

- `PUBLISH=false`（默认）：只构建并 `upload-artifact`，**不** create GitHub Release / **不** 上传 Modrinth。
- 通网后改为 `true`（或去掉 `if: false` 护栏）并配置 secrets。

## Secrets（通网后配置）

| Secret | 仓 | 用途 |
|--------|----|------|
| `GITHUB_TOKEN` | 三仓 | 默认可用；写 Release、推 tag（若 workflow 有权限） |
| `MODRINTH_TOKEN` | 主仓 | 上传模组 jar |
| `MODRINTH_PROJECT_ID` | 主仓 | Modrinth 项目 id（占位，创建项目后填入） |
| `ANDROID_KEYSTORE_BASE64` | Client | 可选；缺省则 debug 签名并在说明中标注 |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | Client | 与上配套 |
| Windows / Tauri 签名相关 | Client | 可选；首期可不配 |

## 通网启用检查表

见 [BRANCH_PROTECTION.md](BRANCH_PROTECTION.md)。启用发布时额外勾选：

- [ ] 三仓 `release.yml` 中 `PUBLISH` 改为 `true`（或删除 publish 步骤的 `if: false`）
- [ ] 主仓配置 `MODRINTH_TOKEN` + `MODRINTH_PROJECT_ID`
- [ ] Client 是否使用正式 Android keystore（可选）
- [ ] 用一次 `workflow_dispatch` 干跑验证 artifact，再开自动 Release
