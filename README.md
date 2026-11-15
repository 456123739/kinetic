# Kinetic 资源归档分支（`resources`）

这个分支**不是**模组源码，也不参与 Gradle 构建。它只存放那些"不算源码、但重做工作还需要"的中间产物：
离线渲染图、分析输出、第三方参考包、崩溃报告、反编译源码、构建日志。

它们原本散落在开发机上的 `.gitignore` 目录里（`analysis/`、`ragdollified-ref/`、`logs/` …），
既没有备份，又占着可观的磁盘。搬到这个分支的目的有三个：

1. **释放本地磁盘** —— 主工作树里可以放心删掉，随时能取回；
2. **可复现** —— 每份归档都带 sha256，拉回来能确认一字未改；
3. **可追溯** —— 归档记录了它对应 `main` 的哪个提交，图能对上当时的代码。

> `main` 分支保持干净：克隆默认只拿源码，不会因为这批资源多下 28 MB。

## 目录约定

```
MANIFEST.json            机器可读清单（唯一事实来源：文件清单、sha256、大小、来源、还原位置）
NOTICE-third-party.md    第三方素材声明
bundles/                 归档本体
  renders/                 目录型：直接是文件树，可在网页上直接看 PNG
  lod-analysis/            目录型
  gunpacks-eos.tar.zst     归档型：压缩后单文件
  crash-reports.tar.zst
  tooling.tar.zst
  ragdollified-src.tar.zst
  logs.tar.zst
```

本分支只放数据。取回与清单维护的脚本在 `main` 的 `scripts/` 下
（`fetch-resources.sh`、`build-manifest.py`），跟源码一起版本化，避免两份实现漂移。

两种 bundle：

| 类型 | 形态 | 为什么 |
| --- | --- | --- |
| `dir` | 明文目录 | 内容本身压不动（PNG），而且要在 GitHub 上直接翻看 |
| `archive` | `tar.zst` 单文件 | 文本多、压得动（枪包 45 MB→13 MB），或者文件名带乱码不便浏览 |

## 取回

在 `main` 的检出里执行（脚本会自动拉取本分支并把 MANIFEST.json 取出来）：

```bash
./scripts/fetch-resources.sh --list                # 看有哪些
./scripts/fetch-resources.sh renders               # 取一份，按 MANIFEST 的 restore_to 放好
./scripts/fetch-resources.sh --all                 # 全取
./scripts/fetch-resources.sh --to /tmp/x renders   # 换个地方解
./scripts/fetch-resources.sh --verify renders      # 只校验现有副本
```

脚本会先校验 sha256，不通过就停下，不会用坏文件覆盖你本地的东西。

不想用脚本也行，直接 checkout：

```bash
git fetch origin resources
git checkout FETCH_HEAD -- bundles/renders          # 目录型
git show FETCH_HEAD:bundles/tooling.tar.zst | zstd -dc | tar -x -C /path/to/kinetic
```

## 版本管理

- 一个 bundle 一份目录/一个文件，**名字稳定**；内容更新就原地替换，历史留在提交里。
- 每次变更单独一次提交，提交信息写清"新增/替换了哪份、为什么、对应哪个 main 提交"。
- 快照打标签：`resources/<YYYY-MM-DD>`，需要复刻某个时间点的全套资源就 checkout 标签。
- `MANIFEST.json` 里的 `main_commit` 是归档时 `main` 的提交，渲染图与代码版本因此能对上。
- 改完 bundle 后重跑 `python3 scripts/build-manifest.py` 刷新清单，再一起提交。
- 本分支不含脚本；`build-manifest.py --root <本分支检出目录>` 可以就地重算清单。

## 当前快照

见 `MANIFEST.json` 的 `totals`；执行 `./scripts/fetch-resources.sh --list` 可看逐项说明。

## 注意

- 这个分支永远不要合进 `main`。它和 `main` 没有共同祖先（orphan branch），也不该有。
- `bundles/tooling.tar.zst` 里的 `analysis/prone/*.json` 是姿态的**源文件**，`main` 里的动画 JSON 是由它们生成的。
  改动画应该改源文件再生成，而不是反过来。
