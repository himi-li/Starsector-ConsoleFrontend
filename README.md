# Console Frontend — 远行星号控制台前端按钮面板

> **AI 使用说明 / AI Disclosure**
> 本项目的源代码、文档与发行包，均由作者与 AI 助手协作生成。
> *The source code, documentation, and release artifacts of this project were produced by the author in collaboration with an AI assistant.*
> AI 参与不等于免于审查：所有改动均已在本机游戏中实测通过后再发布。

为 [Console Commands](https://github.com/LazyWizard/console-commands/) 提供的中文按钮式前端面板：
**点击按钮即自动执行对应的控制台命令**，无需记忆命令名与参数。

## English Summary

A Chinese-language button panel for [Console Commands](https://github.com/LazyWizard/console-commands/):
every console command gets a clickable button, so you never have to remember command names or parameters.

- **Hotkey**: `Ctrl + ~` by default; changeable in the LunaLib mod settings.
- **Persistent parameters**: set an amount once — it is remembered until you change it again.
- **ID picker**: for ID arguments (e.g. `additem`) you get a searchable list that shows the
  **in-game display name first, with the raw ID as a grey annotation**.
- **All commands collected automatically**: any command registered by any installed mod appears on the panel.
- **UI language**: Chinese; a "use English labels" toggle is available in the settings.

**Requirements**: Console Commands 4.0.x (`lw_console`) and LazyLib. LunaLib is optional.
**Install**: drop the `ConsoleFrontend` folder into `Starsector/mods/`, then enable it in the launcher.

## 特性

- **热键呼出**：默认 `Ctrl + ~`（反引号），可在游戏内 Mod 设置（LunaLib）中更改。
- **点按钮即执行**：每个按钮对应一条控制台命令，点击后立即执行，输出显示在面板底部日志区。
- **自动收录全部命令**：从 Console Commands 的命令表读取，**任何已装 Mod 注册的命令都会自动出现在面板上**。
- **参数可自定义并记住**：如「加金币」可自由设置数量，设置后默认沿用上次的数值，直到再次修改
  （保存在 `saves/common/config/console_frontend_params.json.data`）。
- **ID 选择器**：需要 ID 的参数（如「加物品」）提供「下拉选择 + 直接输入 + 搜索」三合一，
  列表**以游戏内名称为主，ID 以灰色小字作为注释保留**。
- **中文界面**：界面字体使用游戏内置、且已含 CJK 字形的 `graphics/fonts/victor16.fnt`
  （本面板不打包字体文件，直接读取游戏自带的字库）。
  若未安装汉化包，可在设置中开启 `使用英文标签`。

## 安装

1. 确认已安装 **Console Commands 4.0.x**（id: `lw_console`）与 **LazyLib**。
2. 从本仓库的 **Releases** 页面下载 `ConsoleFrontend-0.1.1.zip` 并解压。
3. 把 `ConsoleFrontend` 文件夹放入 `Starsector/mods/`。
4. 在启动器中启用 **Console Frontend**。
5. 进入存档后按 `Ctrl + ~` 呼出面板。

也可以直接克隆本仓库：仓库内已包含编译好的 `jars/ConsoleFrontend.jar`，与发行包内的 jar 完全一致，
无需自备 JDK 即可使用（若要自行构建，见下节）。

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
| `-StarsectorDir <路径>` | 远行星号根目录（**无内置默认值**，须自行提供） |
| `-JdkDir <路径>` | JDK 的 bin 目录（**无内置默认值**，须自行提供） |
| `-Deploy` | 构建后复制到 `mods\ConsoleFrontend` |
| `-Verify` | 构建后运行数据文件一致性校验 |

仓库本身不含任何本机绝对路径。请用下列任一方式提供以上两个路径：

```powershell
# 方式一：命令行参数
.\build.ps1 -StarsectorDir 'D:\Games\Starsector' -JdkDir 'C:\Program Files\Java\jdk-21\bin'

# 方式二：环境变量 STARSECTOR_DIR / JAVAC_BIN_DIR
$env:STARSECTOR_DIR = 'D:\Games\Starsector'
$env:JAVAC_BIN_DIR  = 'C:\Program Files\Java\jdk-21\bin'

# 方式三：在仓库根目录建一个 .build.local.ps1（已列入 .gitignore，不会被提交）
$StarsectorDir = 'D:\Games\Starsector'
$JdkDir        = 'C:\Program Files\Java\jdk-21\bin'
```

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
- **必需**：LazyLib（`lw_lazylib`）—— Console Commands 的运行依赖，需一并安装并启用。
- **可选**：LunaLib（`lunalib`）—— 提供游戏内设置界面；未安装时使用内置默认值。

## 许可

见 `LICENSE`。
