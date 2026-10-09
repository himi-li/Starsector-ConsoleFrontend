<#
    Console Frontend — 构建脚本
    用法:
        .\build.ps1                     # 编译并打包到 jars/ConsoleFrontend.jar
        .\build.ps1 -Verify             # 额外运行数据文件一致性校验
        .\build.ps1 -Deploy             # 额外部署到游戏 mods 目录
        .\build.ps1 -Deploy -Verify     # 全流程
#>
[CmdletBinding()]
param(
    [string]$StarsectorDir = '',
    [string]$JdkDir = '',
    [switch]$Deploy,
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Fail($msg) {
    Write-Host "错误: $msg" -ForegroundColor Red
    exit 1
}

function Info($msg) {
    Write-Host $msg -ForegroundColor Cyan
}

function Ok($msg) {
    Write-Host $msg -ForegroundColor Green
}

# ---------- 0. 本机路径（不进入版本库） ----------
# 取值优先级：命令行参数 > 环境变量 > 仓库根目录下未跟踪的 .build.local.ps1
# 仓库本身不携带任何本机绝对路径；.build.local.ps1 已列入 .gitignore。
$localCfg = Join-Path $root '.build.local.ps1'
if (Test-Path $localCfg) { . $localCfg }
if (-not $StarsectorDir) { $StarsectorDir = $env:STARSECTOR_DIR }
if (-not $JdkDir) { $JdkDir = $env:JAVAC_BIN_DIR }
if (-not $StarsectorDir) {
    Fail '未指定远行星号根目录。请用 -StarsectorDir <路径>，或设置环境变量 STARSECTOR_DIR，或在仓库根目录创建 .build.local.ps1。'
}
if (-not $JdkDir) {
    Fail '未指定 JDK 的 bin 目录。请用 -JdkDir <路径>，或设置环境变量 JAVAC_BIN_DIR，或在仓库根目录创建 .build.local.ps1。'
}

# ---------- 1. 定位工具链与依赖 ----------
$javac = Join-Path $JdkDir 'javac.exe'
$jar = Join-Path $JdkDir 'jar.exe'
if (-not (Test-Path $javac)) { Fail "找不到 javac: $javac（用 -JdkDir 指定 JDK 的 bin 目录）" }
if (-not (Test-Path $jar)) { Fail "找不到 jar: $jar" }

$core = Join-Path $StarsectorDir 'starsector-core'
$classpath = @(
    (Join-Path $core 'starfarer.api.jar'),
    (Join-Path $core 'fs.common_obf.jar'),
    (Join-Path $core 'json.jar'),
    (Join-Path $core 'lwjgl.jar'),
    (Join-Path $core 'log4j-1.2.9.jar'),
    (Join-Path $StarsectorDir 'mods\Console Commands\jars\lw_Console.jar'),
    (Join-Path $StarsectorDir 'mods\Lunalib\jars\LunaLib.jar')
)
foreach ($cp in $classpath) {
    if (-not (Test-Path $cp)) { Fail "缺少依赖: $cp" }
}
$cpJoined = $classpath -join ';'

# ---------- 2. 编译 ----------
$buildDir = Join-Path $root 'build\classes'
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$sources = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
if (-not $sources -or $sources.Count -eq 0) { Fail 'src 下没有找到 .java 源文件' }

Info "编译 $($sources.Count) 个源文件..."
& $javac --release 17 -encoding UTF-8 -Xlint:-options -nowarn -cp $cpJoined -d $buildDir $sources
if ($LASTEXITCODE -ne 0) { Fail "javac 失败（exit $LASTEXITCODE）" }
Ok '编译通过'

# ---------- 3. 打包 ----------
$jarDir = Join-Path $root 'jars'
New-Item -ItemType Directory -Force -Path $jarDir | Out-Null
$jarPath = Join-Path $jarDir 'ConsoleFrontend.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

& $jar --create --file $jarPath -C $buildDir .
if ($LASTEXITCODE -ne 0) { Fail "jar 打包失败（exit $LASTEXITCODE）" }
$size = (Get-Item $jarPath).Length
Ok "已生成 jars/ConsoleFrontend.jar ($size 字节)"

# ---------- 4. 校验 ----------
if ($Verify) {
    Info '运行数据文件一致性校验...'

    $labelsPath = Join-Path $root 'data\strings\frontend_labels.json'
    if (-not (Test-Path $labelsPath)) { Fail '缺少 data/strings/frontend_labels.json' }
    try {
        $labels = Get-Content $labelsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Fail "frontend_labels.json 不是合法 JSON: $_"
    }

    # 4.1 收集所有已启用 Mod 的 commands.csv 里的命令名
    $known = New-Object System.Collections.Generic.HashSet[string]
    $csvs = Get-ChildItem (Join-Path $StarsectorDir 'mods') -Recurse -Filter 'commands.csv' -File -ErrorAction SilentlyContinue
    foreach ($csv in $csvs) {
        $lines = Get-Content $csv.FullName -Encoding UTF8 -ErrorAction SilentlyContinue
        if (-not $lines) { continue }
        foreach ($line in $lines) {
            if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#') -or $line.StartsWith('command,')) { continue }
            $name = ($line -split ',')[0].Trim()
            if ($name) { [void]$known.Add($name.ToLower()) }
        }
    }
    Info "  已从 $($csvs.Count) 个 commands.csv 收录 $($known.Count) 个命令名"

    # 4.2 校验精选条目
    $bad = @()
    $tplBad = @()
    $typeBad = @()
    foreach ($prop in $labels.commands.PSObject.Properties) {
        $cmd = $prop.Name.ToLower()
        $def = $prop.Value

        if (-not $known.Contains($cmd)) {
            $bad += "$($prop.Name) (commands 键不是已知命令)"
        }

        $keys = @()
        if ($def.params) { foreach ($p in $def.params) { $keys += $p.key } }

        # run 模板里的 %key% 必须有对应参数
        $runs = @()
        if ($def.run -is [array]) { $runs = $def.run } elseif ($def.run) { $runs = @($def.run) }
        foreach ($r in $runs) {
            foreach ($m in [regex]::Matches($r, '%([A-Za-z0-9_]+)%')) {
                $k = $m.Groups[1].Value
                if ($keys -notcontains $k) {
                    $tplBad += "$($prop.Name): run 里的 %$k% 没有对应参数"
                }
            }
            $first = ($r -split '\s+')[0]
            if ($first -and -not $known.Contains($first.ToLower())) {
                $bad += "$($prop.Name): run 首词 '$first' 不是已知命令"
            }
        }

        # id/enum 类型必须有 source / options
        if ($def.params) {
            foreach ($p in $def.params) {
                if ($p.type -eq 'id' -and -not $p.source) {
                    $typeBad += "$($prop.Name).$($p.key): type=id 缺少 source"
                }
                if ($p.type -eq 'enum' -and -not $p.options) {
                    $typeBad += "$($prop.Name).$($p.key): type=enum 缺少 options"
                }
                if (($p.type -eq 'int' -or $p.type -eq 'float') -and $null -ne $p.default) {
                    $d = 0.0
                    if (-not [double]::TryParse([string]$p.default, [ref]$d)) {
                        $typeBad += "$($prop.Name).$($p.key): default '$($p.default)' 不是数字"
                    } elseif ($null -ne $p.min -and $d -lt [double]$p.min) {
                        $typeBad += "$($prop.Name).$($p.key): default $d 小于 min $($p.min)"
                    } elseif ($null -ne $p.max -and $d -gt [double]$p.max) {
                        $typeBad += "$($prop.Name).$($p.key): default $d 大于 max $($p.max)"
                    }
                }
            }
        }
    }

    if ($bad.Count -gt 0) {
        Write-Host '  命令名问题:' -ForegroundColor Yellow
        $bad | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
    }
    if ($tplBad.Count -gt 0) {
        Write-Host '  run 模板问题:' -ForegroundColor Yellow
        $tplBad | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
    }
    if ($typeBad.Count -gt 0) {
        Write-Host '  参数类型问题:' -ForegroundColor Yellow
        $typeBad | ForEach-Object { Write-Host "    - $_" -ForegroundColor Yellow }
    }
    if ($bad.Count + $tplBad.Count + $typeBad.Count -eq 0) {
        Ok '  数据文件校验通过'
    } else {
        Fail "数据文件校验发现 $($bad.Count + $tplBad.Count + $typeBad.Count) 个问题"
    }

    # 4.3 校验其他 JSON
    foreach ($j in @('mod_info.json', 'data\config\settings.json', 'data\config\LunaSettingsConfig.json')) {
        $p = Join-Path $root $j
        try { Get-Content $p -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null }
        catch { Fail "$j 不是合法 JSON: $_" }
    }
    # 4.4 校验 CSV 列数一致
    foreach ($c in @('data\console\commands.csv', 'data\config\LunaSettings.csv')) {
        $p = Join-Path $root $c
        $lines = Get-Content $p -Encoding UTF8
        $expected = ($lines[0] -split ',').Count
        for ($i = 1; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $count = ([regex]::Matches($line, ',')).Count + 1
            if ($count -ne $expected) {
                Fail "$c 第 $($i + 1) 行列数为 $count，表头为 $expected"
            }
        }
    }
    Ok '  JSON / CSV 校验通过'

    # 4.5 校验 jar 内容
    $entries = & $jar --list --file $jarPath
    $classCount = ($entries | Where-Object { $_ -like 'org/dsh/frontend/*.class' }).Count
    if ($classCount -lt 10) { Fail "jar 内类文件过少（$classCount）" }
    Ok "  jar 内含 $classCount 个类文件"
}

# ---------- 5. 部署 ----------
if ($Deploy) {
    $dest = Join-Path $StarsectorDir 'mods\ConsoleFrontend'
    Info "部署到 $dest ..."

    # 注意：不要先 Remove-Item 整个目录。
    # 若删除后中途失败（例如 jar 被占用），会留下一个残缺的 mod 目录，
    # 游戏读到缺文件的 mod 会直接报错。改为逐项覆盖 + 建目录。
    New-Item -ItemType Directory -Force -Path (Join-Path $dest 'jars') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $dest 'data') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $dest 'graphics') | Out-Null

    Copy-Item (Join-Path $root 'mod_info.json') $dest -Force
    Copy-Item (Join-Path $root 'README.md') $dest -Force -ErrorAction SilentlyContinue
    Copy-Item (Join-Path $root 'LICENSE') $dest -Force -ErrorAction SilentlyContinue
    Copy-Item $jarPath (Join-Path $dest 'jars') -Force
    Copy-Item (Join-Path $root 'data\*') (Join-Path $dest 'data') -Recurse -Force
    if (Test-Path (Join-Path $root 'graphics')) {
        Copy-Item (Join-Path $root 'graphics\*') (Join-Path $dest 'graphics') -Recurse -Force
    }

    # 部署后自检（含贴图）
    $need = @(
        'mod_info.json',
        'jars\ConsoleFrontend.jar',
        'data\console\commands.csv',
        'data\config\settings.json',
        'data\config\LunaSettings.csv',
        'data\config\LunaSettingsConfig.json',
        'data\strings\frontend_labels.json'
    )
    foreach ($f in $need) {
        if (-not (Test-Path (Join-Path $dest $f))) { Fail "部署后缺少 $f" }
    }
    Ok "部署完成: $dest"
    Write-Host '  请重启游戏，在启动器中启用 Console Frontend。' -ForegroundColor Yellow
}

Write-Host ''
Ok '构建完成'