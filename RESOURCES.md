# 资源归档

源码之外还有一类东西：离线渲染图、分析输出、第三方参考包、崩溃报告、反编译源码、构建日志。
它们不进构建、不该进 `main` 的历史，但重做某些工作时又要用。

它们放在本仓库的 `resources` 分支上（孤儿分支，与 `main` 无共同祖先）。
克隆默认只拿源码，需要时再取。

## 为什么不放 `main`

- 合计约 62 MiB 原始体积，跟源码无关，却会拖慢每一次克隆；
- 多数是被 `.gitignore` 排除的生成物，本就不属于主线历史；
- 单独一条分支可以让它们反复替换，而不污染源码提交。

## 取回

在 `main` 的检出里执行：

```bash
./scripts/fetch-resources.sh --list                # 看有哪些
./scripts/fetch-resources.sh renders               # 取一份，落到清单规定的默认位置
./scripts/fetch-resources.sh --all                 # 全取
./scripts/fetch-resources.sh --to /tmp/x renders   # 换个落点
./scripts/fetch-resources.sh --verify renders      # 只校验已有副本
```

脚本先拉取 `resources` 分支，按 `MANIFEST.json` 逐文件比对 sha256，通过后才落盘；
不通过就停，不会用坏文件覆盖本地内容。

不用脚本也可以直接取：

```bash
git fetch origin resources
git checkout FETCH_HEAD -- bundles/renders                          # 目录型
git show FETCH_HEAD:bundles/tooling.tar.zst | zstd -dc | tar -x -C . # 归档型
```

## 清单与校验

`resources` 分支根部的 `MANIFEST.json` 是唯一事实来源：每份 bundle 的文件清单、sha256、字节数、
原始来源、默认还原位置，以及归档时对应的 `main` 提交。

改完 bundle 后重算清单：

```bash
python3 scripts/build-manifest.py --root <resources 分支检出目录>
```

## 维护规则

- 一份 bundle 一个稳定名字；内容更新就地替换，历史留在提交里，不新建 `xxx-v2`。
- 每次变更单独一次提交，说清"换了哪份、为什么、对应哪个 `main` 提交"。
- 快照打标签 `resources/<日期>`，需要复刻某个时点的全套资源就 checkout 标签。
- 从未压缩收益的明文目录（渲染图、分析图）保留为目录，方便直接在网页上翻看；
  文本占比高的（枪包、崩溃报告、日志、反编译源码）打成 `tar.zst`。
- `resources` 分支永远不要合进 `main`。

## 第三方内容

`bundles/gunpacks-eos.tar.zst` 与 `bundles/ragdollified-src.tar.zst` 是第三方素材，仅作分析输入，
不属于本项目、不随模组分发；详见该分支的 `NOTICE-third-party.md`。仓库若转公开，应先剥离这两项。
