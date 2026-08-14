# WebSocket 库存推送协议

默认与 HTTP **同端口**（`websocket.port = 0`）；也可在配置中设独立端口（如 `8766`）。

## 发现

1. `GET /api/v1/health`
2. 若 `websocket.enabled === false` → 不连接
3. 否则连 `ws(s)://{httpHost}:{websocket.port}`
   - `port` 为对外端口；`sameAsHttp: true`（或与 HTTP 端口相同）时即 HTTP 端口
   - 同端口时服务端用协议分流（`Upgrade: websocket` → WS，其它 → HTTP）

## 消息（JSON，字段 `type`）

### Server → Client

| type | 说明 |
|------|------|
| `hello` | 开连；含 `service`、`pushIntervalMs` |
| `auth_ok` | 鉴权成功；含 `account`、`pushIntervalMs` |
| `storage.snapshot` | 库存快照：`revision`、`contentRevision?`、分页、`items`、`network`、`pushIntervalMs` |
| `pong` | 对 `ping` 的响应 |
| `ok` | 通用确认（如 unsubscribe） |
| `error` | `{ code, message }` |

### Client → Server

| type | 说明 |
|------|------|
| `auth` | `{ token }`（与 HTTP Bearer 同一会话 token） |
| `subscribe` | `channel: "storage"` + `q` / `kind` / `filter` / `sort` / `order` / `page` / `pageSize` |
| `unsubscribe` | 取消订阅 |
| `ping` | 心跳 |

`pushIntervalMs` 仅服务端可调；前端只读（health / hello / auth_ok / snapshot）。
