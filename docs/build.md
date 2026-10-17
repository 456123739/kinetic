# 构建指南

当前 Java 工程依赖单独构建的 native 库。Native 与 JAR 的构建和打包流程近期会集成到 GitHub CI；在此之前，本地运行和构建都需要手动准备 native 库。

## 开发环境运行

1. 按照 native 仓库中的 `docs/build_cn.md` 构建当前平台的 native 库。
2. 在 Java 项目根目录修改 `local.properties`，将 `kinetic.native_path` 设置为生成的 native 库的绝对路径。
    - 如果该文件尚不存在，先执行一次下一步的 `runClient`；Gradle 会在首次运行时自动生成该文件，修改后再重新运行。
    - `local.properties` 仅保存本机开发配置，已被 Git 忽略，不会被版本控制追踪。
    - Windows 路径建议使用 `/`，避免 `.properties` 文件将反斜杠解释为转义字符。
3. 在 Java 项目根目录运行客户端：

   ```bash
   ./gradlew runClient
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat runClient
   ```

## 构建 JAR

1. 按照同一份 native 构建指南，构建需要支持的各平台 native 库。
2. 将这些库放入 `src/main/resources/META-INF/native/`。当前运行时使用的资源文件名为：Windows x86_64 的 `kinetic.dll`、GNU/Linux x86_64 的 `libkinetic.so`，以及 Android arm64 的 `libkinetic-android.so`。Android 构建生成的 `libkinetic.so` 需要以 `libkinetic-android.so` 放入该目录。
3. 在 Java 项目根目录运行：

   ```bash
   ./gradlew shadowJar
   ```

   Windows PowerShell 使用：

   ```powershell
   .\gradlew.bat shadowJar
   ```

最终 JAR 位于 `build/libs/`。

## 发布与校验

发布到 `456123739/kinetic-releases`，上传后必须回读远端制品并核对大小与 SHA-256，不能只看上传命令退出码：

```bash
gh release upload v3.0-dev build/libs/kinetic-3.0-dev-forge+mc1.20.1.jar \
    --repo 456123739/kinetic-releases --clobber
```

**回读优先走镜像，不要直连 GitHub。** 本机对 `github.com` 的 release 直链下载不可用（`raw.githubusercontent.com` 同样不通），
直连会一直挂住。首选 `ghfast.top`；`gh-proxy.com` 也通但会限流，返回 37 字节的
`Access to this resource is forbidden.`，遇到就换：

```bash
curl -sL -o /tmp/relverify/kinetic.jar \
    "https://ghfast.top/https://github.com/456123739/kinetic-releases/releases/latest/download/kinetic-3.0-dev-forge%2Bmc1.20.1.jar"
sha256sum /tmp/relverify/kinetic.jar   # 必须与 build/libs/ 下同名校验和一致
```

镜像整体不可用时才退到 GitHub API 核对制品的 `size` 与 `digest`（权威来源，不需要下载，但不是首选）：

```bash
curl -s https://api.github.com/repos/456123739/kinetic-releases/releases/tags/v3.0-dev \
  | python3 -c 'import json,sys; [print(a["name"], a["size"], a.get("digest")) for a in json.load(sys.stdin)["assets"] if a["name"].startswith("kinetic-3.0-dev-forge")]'
```

内嵌 native 的校验和是随源码提交的固定值，重建后应保持不变：`kinetic.dll`
`5d698db97477e418ce88aeef441473649c939adf172b883bfef9dcf6c816170e`、`libkinetic.so`
`519b21cf1bdc0dfd3275479af361bc1a92421a6a1f7b240f8723cb5aa231d546`。

这两个二进制由上游 native 工程构建，其中用于 `FindClass` + `RegisterNatives` 的 Java 类描述符
串已按本模组的包名就地改写（`com/elfmcys/ysm/natives` → `com/kinetic/natives`，串只变短，不移动
任何字节），文件内的 SONAME 与库名同步改写。**其余 `ysm::` 拼写是编译进符号表与 RTTI 的 C++ 命名
空间**（以及 iguana 反射类型名、legacy `.ysm` 扩展名），改动它们会破坏动态链接与 legacy 导入，
因此保持不变；它们不可见于任何 Mod 界面、配置或日志。
