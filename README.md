# Console Frontend — 远行星号控制台前端按钮面板

为 [Console Commands](https://github.com/LazyWizard/console-commands/) 提供的中文按钮式前端面板：
**点击按钮即自动执行对应的控制台命令**，无需记忆命令名与参数。

## 特性

- **热键呼出**：默认 `Ctrl + ~`（反引号），可在游戏内 Mod 设置（LunaLib）中更改。
- **点按钮即执行**：每个按钮对应一条控制台命令，点击后立即执行，输出显示在面板底部日志区。
- **自动收录全部命令**：从 Console Commands 的命令表读取，**任何已装 Mod 注册的命令都会自动出现在面板上**。
- **参数可自定义并记住**：如「加金币」可自由设置数量，设置后默认沿用上次的数值，直到再次修改
  （保存在 `saves/common/config/console_frontend_params.json.data`）。
- **ID 选择器**：需要 ID 的参数（如「加物品」）提供「下拉选择 + 直接输入 + 搜索」三合一，
  列表**以游戏内名称为主，ID 以灰色小字作为注释保留**。
- **中文界面**：依赖游戏中文汉化包的字体（`victor10` / `insignia15LTaa` 已含 CJK 字形）。
  若未安装汉化包，可在设置中开启 `使用英文标签`。

## 安装

1. 确认已安装 **Console Commands 4.0.x**（id: `lw_console`）与 **LazyLib**。
2. 把 `ConsoleFrontend` 文件夹放入 `Starsector/mods/`。
3. 在启动器中启用 **Console Frontend**。
4. 进入存档后按 `Ctrl + ~` 呼出面板。

## 构建

需要 JDK 17 或更高版本（本项目用 `javac --release 17` 编译，目标为游戏内置的 Java 17 运行时）。

```powershell
# 只编译打包（产物在 jars/ConsoleFrontend.jar）
.\build.ps1

# 编译 + 自动部署到游戏 mods 目录
.\build.ps1 -Deploy
```

可用参数：

| 参数 | 说明 |
|---|---|
| `-StarsectorDir <路径>` | 远行星号根目录，默认 `C:\Games\Starsector` |
| `-JdkDir <路径>` | JDK 的 bin 目录，默认 `C:\Program Files\BellSoft\LibericaJDK-21\bin` |
| `-Deploy` | 构建后复制到 `mods\ConsoleFrontend` |
| `-Verify` | 构建后运行数据文件一致性校验 |

## 自定义按钮

编辑 `data/strings/frontend_labels.json`：

```json
{
  "commands": {
    "addcredits": {
      "label": "加金币",
      "category": "economy",
      "desc": "舰队账户增加指定数量的星币",
      "params": [
        { "key": "amount", "label": "数量", "type": "int", "default": 1000000, "min": 1 }
      ],
      "run": "addcredits %amount%"
    }
  }
}
```

- `params[].type` 支持 `int` / `float` / `text` / `id` / `enum` / `bool`。
- `type: "id"` 需指定 `source`（`commodity` / `special` / `commodity+special` / `weapon` /
  `wing` / `hullmod` / `ship` / `variant` / `ship+variant` / `faction` / `market` /
  `system` / `market+system` / `condition` / `industry` / `submarket` / `shipSystem` /
  `officer` / `personality`）。
- `run` 用 `%key%` 引用参数值；也可以是数组，按顺序执行多条命令。
- 未在 `commands` 中列出的命令会自动生成按钮（标签为命令名，参数从语法推导）。

## 控制台命令

```
consolefrontend [open|close|reload|resetparams]
```

## 依赖

- **必需**：Console Commands（`lw_console`）
- **可选**：LunaLib（`lunalib`）—— 提供游戏内设置界面；未安装时使用内置默认值。

## 许可

见 `LICENSE`。
