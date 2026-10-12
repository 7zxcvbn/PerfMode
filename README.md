# 性能模式强制器（PerfMode）

基于 **Shizuku** 的 Android 性能模式开启工具：首页按「芯片平台 → 品牌 → 机型」选择设备，通过后端的**实机探测**决定能做什么，再执行对应档位的性能提升动作，并提供**快捷磁贴 + 前台通知 + 实时状态监控**与**一键回滚**。

---

## 一、先说清楚能力边界（很重要）

这个工程的分层设计，来自一个必须接受的事实：

| 身份 | 能做什么 | 做不到什么 |
|---|---|---|
| Shizuku **ADB 模式**（shell） | 只读探测 CPU/GPU/温控、`settings get/put`、探测厂商设置键、启动服务、前台服务与磁贴 | **无法可靠写入 `/sys`、`/proc` 下的 cpufreq / devfreq / perfmgr / thermal 节点**（文件属 root:root，SELinux 域仍是 shell） |
| Shizuku **root 模式**（uid 0） | 上述全部 + 直写调度与频率节点、放宽温控 | 仍受 SELinux 策略与内核裁剪限制，不是所有节点都开放 |

因此应用内置三档，**不会把 root 才能做的事包装成 ADB 通用能力**：

| 档位 | 做了什么 | 风险 |
|---|---|---|
| **安全档** | 只探测，不写入任何系统值；给出该机型的官方性能模式入口 | 无 |
| **增强档** | 在这台机器上**真实存在**的设置键里开启性能模式（`settings list` 探测 → `settings put` → 读回校验），找不到就回退官方入口 | 低 |
| **极限档** | root 身份下逐节点直写：CPU policy 频率上下限、GPU 上限、`sched_boost`、schedtune，可选锁定 `performance` / 放宽温控 | 中～高：发热、耗电、稳定性 |

写入前**读备份 → 写入 → 读回校验 → 失败即回滚**，所有修改记入会话，可一键恢复；崩溃或卸载后**重启设备**即可让内核参数回到默认。

---

## 二、环境要求

- Android Studio **Ladybug 或更高**（AGP 8.7.3 需要 JDK **17**）
- Gradle **8.9**（见 `gradle/wrapper/gradle-wrapper.properties`）
- `compileSdk 35` / `minSdk 26` / `targetSdk 35`
- 依赖版本集中在 `gradle/libs.versions.toml`，Shizuku API 默认 `13.1.5`；若你的环境拉不到，降级为 `12.1.0` 即可（用法一致）

**首次打开**：用 Android Studio `File > Open` 选择 `PerfMode` 目录，IDE 会自动下载 Gradle；若本地已有 Gradle，可在根目录执行 `gradle wrapper --gradle-version 8.9` 生成 `gradlew`。

---

## 二·补、打包 APK

### 第 0 步：先跑环境自检

```bash
./check-env.sh
```

它会依次检查 **JDK 17 → Android SDK → 构建入口 → 网络**，缺什么直接告诉你怎么补。四盏灯全绿再往下走。

### 第 1 步：准备环境（只需做一次）

| 依赖 | 要求 | 说明 |
|---|---|---|
| **JDK** | **17**（不能更低） | AGP 8.7 硬性要求。装 [Temurin 17](https://adoptium.net/temurin/releases/?version=17)，Windows 安装时勾选"设置 JAVA_HOME" |
| **Android SDK** | API 35 | 装 Android Studio 就自带；只装命令行工具也行 |
| **网络** | 能连 Maven | 首次编译要下载 Gradle + 依赖，几百 MB |

SDK 路径三种告诉 Gradle 的方式，任选其一：

```bash
export ANDROID_HOME=$HOME/Android/Sdk          # macOS/Linux 临时
```

或在工程根目录建 `local.properties`：

```properties
sdk.dir=/Users/你/Library/Android/sdk          # macOS
sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk   # Windows 注意转义
```

### 第 2 步：出包

**路线 A —— Android Studio（推荐，最省事）**

1. `File > Open` 选择 `PerfMode` 目录
2. 等 IDE 自动 Sync（会顺手生成 `gradlew` 和下载 Gradle）
3. `Build > Generate Signed Bundle / APK > APK`
4. 密钥：选 **Create new…** 自己建一个（自用随便填，记住密码就行）
5. 选 `release`，点 Finish

产物：`app/release/app-release.apk`

**路线 B —— 命令行**

首次需要先让 Android Studio 生成 `gradlew`，或手动执行 `gradle wrapper --gradle-version 8.9`。之后：

```bash
./build.sh
```

想签正式名：复制 `app/keystore.properties.example` 为 `app/keystore.properties` 并填入密钥信息。不填也能出包，只是用调试签名（自用够，上架得签正式名）。

```bash
keytool -genkey -v -keystore app/perfmode.jks -keyalg RSA -keysize 2048 -validity 10000 -alias perfmode
```

### 手上没有电脑？用 GitHub 云端编译（推荐）

你刚才在手机终端跑 `check-env.sh`，结果就是典型情况：**手机上装不了 Android SDK**。原因是 Google 只提供 x86_64 的 SDK 构建工具，手机是 ARM 芯片，即便在 Termux 里装上 JDK 也走不通。**这条路不用死磕。**

工程里已经放好了 GitHub Actions 工作流（`.github/workflows/build-apk.yml`），云端帮你编，手机直接下 APK：

1. 把 `PerfMode` 整个目录传到 GitHub 新仓库（手机可用 GitHub 官方 App 或网页上传）
2. 在仓库页面点 **Actions**，确认 Actions 已启用（Settings > Actions > Allow all）
3. 创建一个标签触发构建：网页上 Releases > **Draft a new release** > 填 `v1` > Publish

等几分钟，同一页面的 Release 里就会出现 `app-release.apk`，**手机浏览器可直接下载，不用登录**。构建日志在 Actions 页能看到，失败了把报错发我。

也可以在 Actions 页选「构建 APK」> **Run workflow** 手动触发，产物在本次运行的 Artifacts 里（下载需登录 GitHub）。

> 云端编译出的 APK 用的是调试签名，自己装自己用完全没问题，只是不能上架应用商店。

### Termux 里 `pkg install` 下载太慢？

默认源在境外，4G 下经常只有 20–30 kB/s。

**正在装就别中断**：强退 apt 会留下半装状态，之后要 `apt --fix-broken install` 才救得回来。让它慢慢装完最省心，装一次就不用再装了。期间**别熄屏、别切后台**，否则下载会暂停。

**想加速，换清华源**（装完再换也行，下次生效）：

```bash
cp $PREFIX/etc/apt/sources.list $PREFIX/etc/apt/sources.list.bak 2>/dev/null
rm -f $PREFIX/etc/apt/sources.list.d/*.list
echo 'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main' > $PREFIX/etc/apt/sources.list
pkg update && pkg install git unzip -y
```

也可以跑官方的交互式换源工具 `termux-change-repo`（方向键选、回车确认），但手机上不好操作，不如上面那条命令直接。

### 国内网络下载不动？

编辑 `gradle.properties`，把 `USE_CN_MIRROR=false` 改成 `true`，然后重新 Sync。这会启用阿里云 + 清华镜像。仍然不行就在 Android Studio 里配 HTTP Proxy（`Settings > HTTP Proxy`）。

### 常见报错

| 报错 | 原因 | 解决 |
|---|---|---|
| `Minimum supported Gradle / JDK 17 required` | JDK 版本不对 | 装 JDK 17，确认 `JAVA_HOME` 指向它 |
| `SDK location not found` | 没告诉 Gradle SDK 在哪 | 建 `local.properties` 或设 `ANDROID_HOME` |
| `Could not resolve …` 卡很久 | 网络被拦 | 开 `USE_CN_MIRROR` 或配代理 |
| `Cannot determine java version from '21'` | JDK 太新 | 切回 JDK 17（AGP 8.7 不支持 21） |
| `gradlew: Permission denied` | 没执行权限 | `chmod +x gradlew` |

## 三、目录结构

```
PerfMode/
├── app/src/main/
│   ├── AndroidManifest.xml          # ShizukuProvider / 前台服务(specialUse) / 磁贴 / 恢复广播
│   ├── assets/profiles.json         # 机型库：平台、品牌、机型、候选设置键、官方入口
│   ├── assets/probe.sh              # 全量只读探测脚本（一次执行，避免高频建进程）
│   ├── assets/sample.sh             # 监控采样脚本
│   └── java/com/perfmode/boost/
│       ├── MainActivity.kt          # Compose 入口、通知权限、Shizuku 状态刷新
│       ├── shizuku/                 # ShizukuManager（状态机）+ PrivilegedExecutor（带超时的执行器）
│       ├── device/                  # DeviceProbe（探测解析）+ ProfileRepository（机型库）
│       ├── perf/PerfEngine.kt       # 三档执行引擎：备份、校验、回滚、会话持久化
│       ├── monitor/Telemetry.kt     # 采样、生效度评分、温度换算
│       ├── service/                 # 前台服务、快捷磁贴、紧急恢复广播
│       └── ui/                      # 机型页 / 监控页 / 日志页
```

---

## 四、使用步骤

1. 安装 **Shizuku**，按它的引导用无线调试（Android 11+）或电脑 ADB 启动服务；非 root 设备**每次重启都要重新启动**。
2. 打开本应用 → 首卡片点「请求授权」。
3. 授权后自动探测当前设备；也可手动按平台/品牌选机型（用于查看适配方案）。
4. 选档位 → 点「应用」。极限档若勾选了锁定调度或放宽温控，会二次确认。
5. 用「监控」页看频率、温度、冷却状态与生效度评分，验证是否真的生效。
6. 不用时点「恢复默认」，或下拉磁贴一键关闭；通知里也有「立即恢复默认」。

**想用极限档**：Shizuku 必须以 **root 方式**启动（Magisk / KernelSU 等），否则应用会自动降级为增强档并在结果里说明原因。

---

## 五、机型库怎么扩

编辑 `app/src/main/assets/profiles.json`：

```json
{
  "id": "mydevice",
  "brand": "xiaomi",
  "name": "小米 XX",
  "platform": "sd8e",
  "alias": ["Xiaomi XX", "MI XX"],
  "confidence": "medium"
}
```

- `platforms`：芯片平台与其 `boards`（用于按 `ro.board.platform` 反查）
- `brandRecipes`：该品牌的**官方入口**与**候选设置键**
- 候选键是「候选项」而非「已验证命令」：运行时先 `settings list` 探测，命中才写，写后读回校验，未命中就回退官方入口。**请不要把社区流传的键名当成必然有效**。

建议在真机上用下面几条命令确认，再写进 JSON：

```bash
settings list system | grep -iE 'perf|performance|mode'
settings list global | grep -iE 'perf|performance'
getprop | grep -iE 'perf|thermal|power'
```

---

## 四·补、全面适配：未收录机型怎么办

机型库现在覆盖 **26 个芯片平台 / 77 款机型**（含骁龙 870/865、888、8 Gen1、778G 等存量机型，iQOO Neo5 已收录）。但**穷举机型不是本工程的可靠性来源**，真正的兜底是四层解析：

| 层级 | 判定依据 | UI 反馈 |
|---|---|---|
| 精确机型 | `model` / `marketname` / 内部代号（如 PD2055）命中机型库 | 「已收录机型：iQOO Neo5」 |
| 芯片识别 | `ro.board.platform` / `ro.chipname` / `ro.soc.model` 命中平台 board | 「未收录该型号，已按芯片识别为 骁龙 870」 |
| 厂商兜底 | 只识别出 QTI / MTK | 「按厂商走 XX 配方」 |
| 纯探测 | 完全未识别 | 「只写这台机器真正暴露且可写的节点」 |

**关键**：执行层本来就只认探测到的可写节点，不认预设机型。所以**不选任何机型也能用**，而且往往最稳——机型库只是用来给品牌设置键提供候选项。首页机型列表底部有「没找到我的机型？用实机探测结果」入口，选中即跳过预设。

共享 board 的芯片（865/870 同为 kona、8 Gen2/8 Gen3 同为 kalama）用 **CPU 最高频辅助区分**：≥3.0GHz 判 870，≥3.25GHz 判 8 Gen3。

---

## 五·补、Unrestrict / qboost / MTK 调频

第四个页签「调频」实现了 KBattery 形态的三件套（**KBattery 是闭源 App，无法参考源码，这里只借鉴功能形态并自行实现**）：

| 功能 | 做了什么 |
|---|---|
| **Unrestrict 策略** | 每簇 `scaling_max_freq` 顶到 `cpuinfo_max_freq`；`core_ctl/min_cpus` 拉满保大核在线；高通 `msm_performance` 释放簇上限；`top-app` cpuset 放开；`schedtune.boost` + `sched_boost` + 迁移阈值让 EAS 更愿意用大核 |
| **qboost 调频** | 最小/最大频率**百分比滑杆**（0–100%），按每簇 `[cpuinfo_min, cpuinfo_max]` 区间换算，同时把 GPU 上限按最大百分比设定 |
| **后台周期维持** | 前台服务按间隔（1–30s，默认 5s）重放已生效的写入，可设「仅亮屏时维持」 |
| **MTK 调频** | 联发科专有的 PPM 定频/定核、`hard_userlimit`、GPU `gpufreq`/`ged`、FPSGO 开关与限频等级、perfmgr 触摸提频 |

**为什么必须有「周期维持」**：vendor 的性能/温控守护进程会周期性把 `scaling_max_freq` 等节点改回去，只写一次通常几秒就失效。这是这类工具真正生效的关键，也是本模块由前台服务托管的原因。

**原版 Qboost 说明**：它是 2017 年基于 Snapdragon Power Optimization SDK 的老工具，只支持骁龙 425–835，现代 8 系用不了。这里复刻的是它的**交互与维持思路**，底层改用通用 cpufreq 节点 + root 写入。

**已知限制**：以上全部节点基本都是 `root:root`，**ADB 身份下会明确返回「需要 root」而不是假装成功**；联发科节点是否暴露取决于内核，未命中会在结果里逐条说明。温控屏蔽（`thermal_manager`）属于高风险动作，本工程**默认不启用**。

---

## 六、实现要点（为什么这么写）

- **一切以探测为准**：机型配方按 `ro.board.platform` + 真实节点可写性路由，不靠型号字符串硬编码命令。
- **不 chmod、不批量 echo**：遇到不可写节点直接跳过并在日志说明，避免触发 SELinux denial 或产生半成品状态。
- **不写死频率数值**：目标值全部来自设备自己的 `cpuinfo_max_freq` / `available_frequencies`。
- **采样合并为单条脚本**：每次采样只创建一次进程，采样本身也会耗电、会抬负载。
- **过热自保护**：前台服务检测到温度连续 3 次 ≥ 47°C 自动恢复默认；Shizuku 掉线也自动恢复。
- **Android 版本适配**：12+ 只从用户可见交互启动前台服务；13+ 运行时申请 `POST_NOTIFICATIONS`（拒绝也能无通知运行）；14+ 声明 `specialUse` 前台服务类型与用途。

---

## 七、风险与免责

- 极限档会显著提高发热与耗电，长时间高负载可能触发系统保护性关机或加速电池老化。
- 本应用不修改系统分区、不解锁 bootloader、不提供永久 root；但 root 操作可能影响保修、完整性校验与游戏反作弊判定，请自行评估。
- 应用崩溃或卸载后无法保证自动恢复，**重启设备**是最可靠的兜底。
- 所有采集与诊断数据只存在本机，不上传、不收集 IMEI 与位置。
