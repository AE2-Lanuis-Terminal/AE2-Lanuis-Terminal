# AE2 Lanuis Terminal

在 **Applied Energistics 2** 的 ME 网络上使用远程终端：用浏览器或桌面 / 手机 App 查看库存、下合成、管理样板。

- 游戏：**Minecraft 1.20.1**（Forge）
- 形态：**只装在服务端**；联机玩家 **不用** 装本模组
- 许可：[MIT](LICENSE)

## 你需要准备什么

1. 已安装 **AE2** 的 Forge 1.20.1 服务端（或单人整合包）
2. 本模组 jar（Release 或自行构建，见 [安装说明](docs/INSTALL.md)）
3. （推荐）预烘焙物品图标，否则网页上图标可能空白——步骤见下方「图标」

可选客户端：

| 方式 | 说明 |
|------|------|
| 浏览器 | 打开模组提供的网页地址（游戏内 `/ae2lanuis status` 可查看） |
| [桌面 / Android 客户端](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal-Client) | 独立 App，登录时填写服务器 IP 与端口 |

## 快速使用

1. 把 `ae2lanuis-*.jar` 放进服务端 `mods/`，启动服务器  
2. 持有已链接到目标网络的**无线终端**，执行：  
   `/ae2lanuis password <你的密码>`  
3. 在浏览器打开提示的地址，或用 Client 填入同一主机与端口，用游戏账号与密码登录  

更完整的配置、命令与 Docker 说明：**[docs/INSTALL.md](docs/INSTALL.md)**。

### 图标（服主做一次即可）

联机玩家不需要烘焙。管理员用与服务器相同的模组列表开**单人世界**，执行：

```text
/ae2lanuis resources render
```

把生成的 `aeKeyResources/` 拷到专用服游戏根目录（与 `config/` 同级）。详见 [安装说明 · 图标](docs/INSTALL.md)。

### 常用命令

| 命令 | 作用 |
|------|------|
| `/ae2lanuis help` | 帮助 |
| `/ae2lanuis password <密码>` | 设密并绑定当前无线终端所在网络 |
| `/ae2lanuis status` | 查看绑定与网页地址 |
| `/ae2lanuis password clear` | 清除本人绑定 |

OP 登录网页后可选择进入**管理端**，查看全服网络与审计。

## 默认端口

- HTTP：`8765`（可在 `config/ae2lanuis-server.toml` 修改）
- WebSocket：默认与 HTTP **同一端口**（`websocket.port = 0`）

## 相关项目

- [Client](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal-Client) — Windows / Android 客户端  
- [Web](https://github.com/AE2-Lanuis-Terminal/AE2-Lanuis-Terminal-Web) — 网页界面源码  

## 文档与开发

| 文档 | 适合谁 |
|------|--------|
| [docs/INSTALL.md](docs/INSTALL.md) | 服主：安装、配置、全部命令 |
| [docs/README.md](docs/README.md) | 文档目录（含开发 / 发版） |
