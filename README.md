# PaperCamera

Paper 服务器的摄像机插件。使用一个旁观者账号作为摄像机，自动环绕玩家和出生点，实现无人值守的直播/展示视角。

## 功能

- **自动环绕**：摄像机以旁观模式环绕目标，可配置半径、高度、速度
- **玩家跟随**：自动轮流跟随在线玩家，支持平滑跟随和速度上限
- **出生点展示**：无人时自动环绕各世界出生点，配合 MultiVerse 等传送插件使用
- **伪随机队列**：Fisher-Yates 洗牌，所有目标遍历一次才重复，无背靠背
- **智能遮挡处理**：射线检测遮挡，自动抬高或推近，带 debounce 防抽搐
- **出生点自动发现**：通过 spawn-command 自动遍历各世界记录出生点坐标
- **跨世界传送**：支持 MultiVerse 等插件，摄像机可跟随玩家进入任何世界
- **远距离瞬移**：目标距离超过阈值时直接传送，避免跑步追赶导致大量区块加载
- **视角平滑**：方向平滑过滤，消除玩家跳跃等快速移动造成的画面抖动
- **热重载**：`/camera reload` 无需重启即可更新配置

## 权限

| 权限节点 | 描述 | 默认 |
|----------|------|------|
| `papercamera.admin` | 控制摄像机系统的全部权限 | OP |

## 指令

| 指令 | 描述 |
|------|------|
| `/camera start` | 启动摄像机系统 |
| `/camera stop` | 停止摄像机系统 |
| `/camera status` | 查看运行状态（运行中、当前目标、在线玩家数、配置世界） |
| `/camera reload` | 热重载配置文件（运行中会重启摄像机） |
| `/camera skip` | 跳过当前目标，切换到下一个 |
| `/camera discoverspawn` | 手动执行出生点发现（使用 spawn-command 遍历 idle-worlds） |

## 快速开始

1. 创建一个名为 `camera` 的 Minecraft 账号，登录到服务器
2. 将 `PaperCamera-1.0.0.jar` 放入 `plugins/` 文件夹
3. 启动服务器，插件加载后摄像机会自动设为旁观模式并开始环绕
4. 如需出生点发现，配置 `spawn-command` 和 `idle-worlds`，然后执行 `/camera discoverspawn`

## 配置说明

插件首次启动后会在 `plugins/PaperCamera/config.yml` 生成完整配置文件，关键配置项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `camera-player-name` | `camera` | 摄像机玩家名称 |
| `auto-start` | `true` | 摄像机上线后是否自动启动 |
| `spawn-discovery-delay` | `5` | 启动前等待秒数（等待地图加载） |
| `orbit.radius` | `8.0` | 环绕半径（格） |
| `orbit.height` | `2.5` | 摄像机高于目标的高度（格） |
| `orbit.speed` | `0.005` | 每 tick 旋转弧度 |
| `follow.min-duration` | `15` | 每个目标最少停留秒数 |
| `follow.max-duration` | `30` | 每个目标最多停留秒数 |
| `follow.teleport-threshold` | `60.0` | 超过此距离（格）直接传送而非追赶 |
| `occlusion.min-distance` | `3.5` | 摄像机到目标的最近距离 |
| `occlusion.max-distance` | `24.0` | 最远距离，超过时加速靠近 |
| `spawn-weight` | `0.3` | 有玩家时非主世界出生点的出现概率 |
| `spawn-command` | `""` | 出生点发现命令前缀（如 `mv tp`） |
| `idle-worlds` | `["world"]` | 出生点世界列表 |

## 依赖

- Paper 1.21+（或兼容的 Bukkit 服务端）
- Java 21
- 可选：MultiVerse 等跨世界传送插件

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/PaperCamera-1.0.0.jar`。