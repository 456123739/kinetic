#!/usr/bin/env python3
"""重新生成 MANIFEST.json。

用途：resources 分支每加/换一份 bundle，就重跑一次本脚本刷新清单。
清单是唯一事实来源：fetch-resources.sh 用它来校验 sha256、决定解包方式。

用法：
    python3 scripts/build-manifest.py [--root .] [--main-commit <sha>]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone, timedelta

TZ = timezone(timedelta(hours=8))

# bundle 元数据（结构信息在磁盘上，这里只写“人话”与来源）
BUNDLES = [
    {
        "name": "renders",
        "kind": "dir",
        "path": "bundles/renders",
        "title": "动画验证渲染",
        "description": "趴下/滑铲/医疗/投掷/挥砍等姿态的多角度离线渲染图，用于在没有客户端时人工核对动作几何。"
                       "文件名前缀即生成顺序，AF1_/AE1_ 是最后一轮挥砍取图。",
        "origin": ["/home/admin/NS/kinetic/analysis/renders"],
        "restore_to": "analysis/renders",
        "generator": "analysis/prone_build.py 等（见 tooling bundle）",
    },
    {
        "name": "lod-analysis",
        "kind": "dir",
        "path": "bundles/lod-analysis",
        "title": "枪械 LOD 分析图",
        "description": "枪械 LOD/背面剔除/轮廓误差的对比图与中间产物，对应 docs/future/gun-lod-decision.md "
                       "里被产品决策阻塞的那条工作线。",
        "origin": ["/home/admin/NS/kinetic/analysis/out"],
        "restore_to": "analysis/out",
        "generator": "analysis/gun_lod_*.py / lod_*.py / cull_*.py",
    },
    {
        "name": "gunpacks-eos",
        "kind": "archive",
        "path": "bundles/gunpacks-eos.tar.zst",
        "title": "TACZ eos 枪包（参考数据）",
        "description": "第三方枪包，仅作 LOD/动画分析的输入样本，不属于 Kinetic 本体，也不随模组分发。"
                       "tar 根为 eos/；上游 eos.zip（15,677,704 字节）解包后即此目录，故未重复收录。",
        "origin": ["/home/admin/NS/kinetic/analysis/gunpacks/eos"],
        "restore_to": "analysis/gunpacks",
        "members_root": "eos",
        "third_party": True,
    },
    {
        "name": "crash-reports",
        "kind": "archive",
        "path": "bundles/crash-reports.tar.zst",
        "title": "崩溃报告存档",
        "description": "四次客户端崩溃的完整报告（含 debug.log）。文件名含原 Windows 中文路径的乱码，"
                       "按字节原样保留。tar 根为 .，四个目录各对应一次崩溃。",
        "origin": [
            "/home/admin/NS/kinetic/analysis/crash-2026-10-05-1806",
            "/home/admin/NS/analysis/crash-2026-10-06-1655",
            "/home/admin/NS/analysis/crash-2026-10-06-2054",
            "/home/admin/NS/analysis/crash-2026-10-06-2102",
        ],
        "restore_to": None,
        "restore_hint": "历史上分散在仓库内 analysis/ 与工作区根 analysis/ 两处；解包后按需放置即可。",
    },
    {
        "name": "tooling",
        "kind": "archive",
        "path": "bundles/tooling.tar.zst",
        "title": "姿态求解/渲染工具链与姿态源文件",
        "description": "analysis/ 下的 Python 求解器、作者姿态 JSON（analysis/prone）、纹理与 Java 参考检查代码。"
                       "这些文件被 .gitignore 排除，只有本归档保留了副本。tar 根为 analysis/，"
                       "解包到仓库根即可复原 analysis/ 工具链。",
        "origin": ["/home/admin/NS/kinetic/analysis"],
        "restore_to": ".",
        "members_root": "analysis",
    },
    {
        "name": "ragdollified-src",
        "kind": "archive",
        "path": "bundles/ragdollified-src.tar.zst",
        "title": "Ragdollified 反编译源码（1.1.1 / 1.2.2）",
        "description": "兼容层依赖的第三方模组反编译源码，用来确认 1.2.2 的 PhysicsHooks/DeadPlayerKeepMixin "
                       "行为。1.2.2-original.jar 是反编译的原始输入。tar 根为 .。",
        "origin": [
            "/home/admin/NS/kinetic/ragdollified-ref/src",
            "/tmp/rag122/src",
        ],
        "restore_to": None,
        "restore_hint": "1.1.1 放回 kinetic/ragdollified-ref/src 可继续阅读；1.2.2 用 Vineflower 重新反编译也可得到。",
        "third_party": True,
    },
    {
        "name": "logs",
        "kind": "archive",
        "path": "bundles/logs.tar.zst",
        "title": "构建/测试/运行日志",
        "description": "工作区根目录的 build-*.log / test-*.log / *.patch，以及 kinetic/logs 的运行期日志。"
                       "tar 根为 .，分 workspace-logs/ 与 kinetic-logs/ 两个子目录。",
        "origin": ["/home/admin/NS/*.log", "/home/admin/NS/*.patch", "/home/admin/NS/kinetic/logs"],
        "restore_to": None,
        "restore_hint": "workspace-logs/ 放回工作区根，kinetic-logs/ 放回 kinetic/logs。",
    },
]


def sanitize_path(p: str) -> str:
    """非 UTF-8 的路径（原 Windows 中文名解包出来的乱码）转成 \\xNN 文本，保证 JSON 合法。"""
    try:
        p.encode("utf-8")
        return p
    except UnicodeEncodeError:
        return p.encode("utf-8", "surrogateescape").decode("utf-8", "backslashreplace")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def walk(root: str) -> list[dict]:
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root)
            files.append({"path": sanitize_path(rel), "bytes": os.path.getsize(full), "sha256": sha256_file(full)})
    return files


def dir_digest(files: list[dict]) -> str:
    h = hashlib.sha256()
    for entry in files:
        h.update(f"{entry['path']}\0{entry['sha256']}\0{entry['bytes']}\n".encode("utf-8", "surrogateescape"))
    return h.hexdigest()


def tar_members(archive: str) -> list[dict]:
    """列出 tar.zst 的成员（不含目录）。用 tarfile 流式读，避免解析 ls 风格的列。"""
    import tarfile

    members = []
    try:
        proc = subprocess.Popen(["zstd", "-dc", archive], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError:
        return members
    try:
        with tarfile.open(fileobj=proc.stdout, mode="r|") as tf:
            for info in tf:
                if info.isdir():
                    continue
                members.append({"path": sanitize_path(info.name), "bytes": info.size})
    finally:
        if proc.stdout:
            proc.stdout.close()
        proc.wait()
    return members


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--main-commit", default=None)
    args = parser.parse_args()

    root = os.path.abspath(args.root)
    if args.main_commit is None:
        try:
            args.main_commit = subprocess.run(
                ["git", "-C", "/home/admin/NS/kinetic", "rev-parse", "main"],
                capture_output=True, text=True, check=True).stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            args.main_commit = None

    bundles = []
    for spec in BUNDLES:
        entry = dict(spec)
        full = os.path.join(root, spec["path"])
        if not os.path.exists(full):
            print(f"[skip] 缺少 {spec['path']}", file=sys.stderr)
            continue
        if spec["kind"] == "dir":
            files = walk(full)
            entry["file_count"] = len(files)
            entry["bytes_raw"] = sum(f["bytes"] for f in files)
            entry["bytes_archived"] = entry["bytes_raw"]
            entry["sha256"] = dir_digest(files)
            entry["files"] = files
        else:
            members = tar_members(full)
            entry["file_count"] = len(members)
            entry["bytes_raw"] = sum(m["bytes"] for m in members)
            entry["bytes_archived"] = os.path.getsize(full)
            entry["sha256"] = sha256_file(full)
            entry["members"] = members
        bundles.append(entry)

    manifest = {
        "schema": 1,
        "repo": "456123739/kinetic",
        "branch": "resources",
        "generated_at": datetime.now(TZ).isoformat(timespec="seconds"),
        "main_commit": args.main_commit,
        "totals": {
            "bundles": len(bundles),
            "bytes_raw": sum(b["bytes_raw"] for b in bundles),
            "bytes_archived": sum(b["bytes_archived"] for b in bundles),
        },
        "bundles": bundles,
    }

    out = os.path.join(root, "MANIFEST.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2, sort_keys=False)
        fh.write("\n")

    print(f"写出 {out}")
    print(f"  bundle {manifest['totals']['bundles']} 个，"
          f"原始 {manifest['totals']['bytes_raw'] / 1048576:.1f} MiB，"
          f"归档 {manifest['totals']['bytes_archived'] / 1048576:.1f} MiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
