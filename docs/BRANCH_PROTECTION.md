# 分支保护（通网后在 GitHub 勾选）

三仓（Web / 主仓 / Client）对 `main` 建议统一设置：

## Settings → Branches → Branch protection rule（`main`）

- [ ] Require a pull request before merging
- [ ] Require approvals（建议 ≥ 1；单人维护可暂 0 但仍强制走 PR）
- [ ] Dismiss stale pull request approvals when new commits are pushed
- [ ] Require status checks to pass before merging
  - Web：`ci` / lint+typecheck+build
  - 主仓：`build`
  - Client：`ci`（desktop / android 烟测）
- [ ] Require branches to be up to date before merging（可选）
- [ ] Do not allow bypassing the above settings
- [ ] Restrict who can push to matching branches（管理员也走 PR）
- [ ] Block force pushes
- [ ] Block deletions

## Actions 权限（Settings → Actions → General）

- [ ] Workflow permissions：Read and write（Release 需要写 contents；或仅对 release workflow 使用 `permissions: contents: write`）
- [ ] Allow GitHub Actions to create and approve pull requests（若后续用 bot 开发行 PR）

## 本地开发提醒

- 功能分支命名建议：`feat/…`、`fix/…`、`release/x.y.z`
- 发行 PR 标题建议带 `[release]`，便于人工识别；真正的自动判定以 `CHANGELOG.md` 新增 `## [X.Y.Z]` 为准（见 [RELEASE.md](RELEASE.md)）
