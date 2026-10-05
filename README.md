# Cobblemon Create Logistics

一个将 Cobblemon 运输员与 Create 包裹物流连接起来的 NeoForge 模组。

Hub 接收 Create 原生包裹，由已分配的方可梦运输员送往对应地址的 Hub。每个 Hub 最多可驻留六名运输员；包裹可由打包机从侧面或底部输入，抵达后可以交给相邻打包机、手动领取或自动提取。站台上的小磁怪和真实包裹会根据任务状态显示动画。

## 安装

- Minecraft 1.21.1
- NeoForge 21.1.244 或更新的 21.1.x 版本
- Create 6.0.10 或更新的兼容版本
- Cobblemon 1.8.1 或更新的兼容版本
- Kotlin for Forge 5.3 或更新的兼容版本

从 [Releases](https://github.com/runwater73-cpu/cobblemon-create-logistics/releases) 下载 JAR，放入客户端和服务器的 `mods` 目录。此模组需要上述依赖，不能单独运行。

## 基本使用

1. 放置两个 Hub，给目的地 Hub 设置接收地址。
2. 在来源 Hub 的界面中，从玩家 PC 分配运输员。每个 Hub 最多六名。
3. 用 Create 打包机将原生包裹送入来源 Hub。包裹自身地址会优先用于选择目的地；无地址包裹可使用来源 Hub 的默认目的地预设。
4. 运输员取件并送往目的地 Hub。到达后，包裹可交给打包机、在界面领取，或通过物品处理接口提取。

放置 Hub 时小磁怪面向玩家；机械动力扳手可旋转朝向。Hub 的侧面和底部可与打包机连接。

跨区块运输不会强制加载区块。来源 Hub 仍需保持实体刻运行；地面路线需要有可行走通道。跨维度路线采用端点交接，**不会**显示运输员跨维度行走动画。

## 构建与测试

需要 Java 21。在仓库根目录运行：

```text
./gradlew check
./gradlew runGameTestServer -PincludeGameTests=true
```

可安装的 JAR 位于 `build/libs/`。正式构建不会包含 GameTest 夹具。

## 许可

项目代码使用 [MIT 许可证](LICENSE)。小磁怪模型与贴图的许可单独列于 [第三方资源说明](THIRD_PARTY_NOTICES.md)，不能按项目的 MIT 许可证再次授权。Create 的纹理通过依赖在运行时引用，未复制进此仓库。

本项目是独立附属模组，与 Cobblemon、Create、Minecraft 或 Pokemon 的权利方没有官方关联。
