#!/usr/bin/env bash
# 从 kinetic 源码仓库的 resources 分支取回归档资源，并逐个校验 sha256。
#
#   ./scripts/fetch-resources.sh --list              列出所有 bundle
#   ./scripts/fetch-resources.sh renders             取回一个 bundle
#   ./scripts/fetch-resources.sh renders tooling     取回多个
#   ./scripts/fetch-resources.sh --all               全部取回
#   ./scripts/fetch-resources.sh --to /tmp/x renders 覆盖解包目标目录
#   ./scripts/fetch-resources.sh --verify renders    只校验已有副本，不覆盖
#
# 默认解包位置见 MANIFEST.json 的 restore_to（相对仓库根）。
# 环境变量：KINETIC_RES_BRANCH（默认 resources）、KINETIC_RES_REMOTE（默认 origin）。
set -euo pipefail

BRANCH="${KINETIC_RES_BRANCH:-resources}"
REMOTE="${KINETIC_RES_REMOTE:-origin}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

die() { echo "错误：$*" >&2; exit 1; }
note() { echo "  $*"; }

TARGET_OVERRIDE=""
VERIFY_ONLY=0
ALL=0
LIST=0
NAMES=()

while [ $# -gt 0 ]; do
    case "$1" in
        --list) LIST=1; shift ;;
        --all) ALL=1; shift ;;
        --verify) VERIFY_ONLY=1; shift ;;
        --to) [ $# -ge 2 ] || die "--to 需要一个目录"; TARGET_OVERRIDE="$2"; shift 2 ;;
        -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) die "未知选项 $1" ;;
        *) NAMES+=("$1"); shift ;;
    esac
done

# ---- 解析 MANIFEST.json 的小工具 -------------------------------------------
# manifest q  <field> <name>          取字段（totals 用 name=-）
# manifest names                      列出所有 bundle 名
manifest() {
    python3 - "$@" <<'PY'
import json, sys
field = sys.argv[1]
rest = sys.argv[2:]
m = json.load(open("MANIFEST.json", encoding="utf-8"))
if field == "names":
    for b in m["bundles"]:
        print(f"{b['name']}\t{b['kind']}\t{b['bytes_archived']}\t{b['title']}")
    sys.exit(0)
if field == "table":
    def w(text):
        return sum(2 if ord(c) > 0x1100 else 1 for c in text)
    rows = [(b["name"], b["kind"], f"{b['bytes_archived'] / 1048576:.1f}M", b["title"]) for b in m["bundles"]]
    heads = ("NAME", "KIND", "归档", "说明")
    cols = [max(w(r[i]) for r in rows + [heads]) for i in range(4)]
    for row in [heads] + rows:
        # 按显示宽度补位，中文占两格
        pad = lambda text, width: text + " " * (width - w(text))
        cells = [pad(row[0], cols[0]), pad(row[1], cols[1]),
                 " " * (cols[2] - w(row[2])) + row[2], row[3]]
        print(f"{cells[0]}  {cells[1]}  {cells[2]}  {cells[3]}")
    print(f"\n合计 {m['totals']['bundles']} 份，原始 {m['totals']['bytes_raw'] / 1048576:.1f} MiB，归档 {m['totals']['bytes_archived'] / 1048576:.1f} MiB")
    sys.exit(0)
name = rest[0]
if name == "-":
    print(m["totals"].get(field, ""))
    sys.exit(0)
for b in m["bundles"]:
    if b["name"] == name:
        v = b.get(field)
        print("" if v is None else v)
        sys.exit(0)
sys.exit(f"MANIFEST 里没有 bundle: {name}")
PY
}

# ---- 取回分支 ---------------------------------------------------------------
fetch_branch() {
    local ref="refs/remotes/$REMOTE/$BRANCH"
    echo "拉取 $REMOTE/$BRANCH ..." >&2
    git fetch --no-tags "$REMOTE" "+refs/heads/$BRANCH:$ref" >/dev/null 2>&1 \
        || die "拉取失败：请确认网络/凭据可用（本机可先起 tools/gh-tunnel.py 并设 HTTPS_PROXY）"
    echo "$REMOTE/$BRANCH"
}

# 把某个路径从 git 树里导出到目录（用 git archive，不碰当前工作树和索引）
export_path() {
    local tree="$1" path="$2" dest="$3"
    git archive "$tree" -- "$path" | tar -xf - -C "$dest" \
        || die "导出失败：$path"
}

# ---- 校验 -------------------------------------------------------------------
verify_dir() { # <dir> <name>
    python3 - "$1" "$2" <<'PY'
import hashlib, json, os, sys
root, name = sys.argv[1], sys.argv[2]
m = json.load(open("MANIFEST.json", encoding="utf-8"))
entry = next(b for b in m["bundles"] if b["name"] == name)
bad = missing = 0
for f in entry["files"]:
    p = os.path.join(root, f["path"])
    if not os.path.isfile(p):
        missing += 1
        continue
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != f["sha256"] or os.path.getsize(p) != f["bytes"]:
        bad += 1
        print(f"    不匹配: {f['path']}")
print(f"    {entry['file_count']} 个文件，缺失 {missing}，损坏 {bad}")
sys.exit(1 if (bad or missing) else 0)
PY
}

verify_archive() { # <archive> <name>
    local actual
    actual="$(sha256sum "$1" | cut -d' ' -f1)"
    local expected; expected="$(manifest sha256 "$2")"
    if [ "$actual" != "$expected" ]; then
        echo "    sha256 不符"; echo "      期望 $expected"; echo "      实际 $actual"; return 1
    fi
    echo "    sha256 $actual"
}

# ---- 主流程 -----------------------------------------------------------------
if [ "$LIST" = 1 ]; then
    manifest table
    exit 0
fi

if [ "$ALL" = 1 ]; then
    while IFS=$'\t' read -r n _; do NAMES+=("$n"); done < <(manifest names)
fi
[ ${#NAMES[@]} -gt 0 ] || die "没有指定 bundle（用 --list 查看，或 --all）"

TREE=""
[ "$VERIFY_ONLY" = 1 ] || TREE="$(fetch_branch)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
[ "$VERIFY_ONLY" = 1 ] || export_path "$TREE" "MANIFEST.json" "$STAGE"

fail=0
for name in "${NAMES[@]}"; do
    kind="$(manifest kind "$name")"
    path="$(manifest path "$name")"
    echo "== $name ($kind)"

    if [ "$VERIFY_ONLY" = 1 ]; then
        dest="$(manifest restore_to "$name")"
        [ -n "$TARGET_OVERRIDE" ] && dest="$TARGET_OVERRIDE"
        if [ "$kind" = dir ]; then
            if [ -n "$TARGET_OVERRIDE" ]; then dest="$TARGET_OVERRIDE/$name"; fi
            [ -n "$dest" ] || dest="."
            echo "    校验 $dest"
            verify_dir "$dest" "$name" || fail=1
        else
            if [ -f "$path" ]; then
                verify_archive "$path" "$name" || fail=1
            else
                echo "    跳过：$path 不在本地（归档型只校验压缩包本身，解包内容请按 MANIFEST 的 members 对账）"
            fi
        fi
        continue
    fi

    export_path "$TREE" "$path" "$STAGE"
    if [ "$kind" = dir ]; then
        verify_dir "$STAGE/$path" "$name" || fail=1
        dest="$REPO_ROOT/$(manifest restore_to "$name")"
        [ -n "$TARGET_OVERRIDE" ] && dest="$TARGET_OVERRIDE/$name"
        mkdir -p "$dest"
        cp -a "$STAGE/$path/." "$dest/"
        note "→ $dest"
    else
        verify_archive "$STAGE/$path" "$name" || fail=1
        dest_override="$TARGET_OVERRIDE"
        restore_to="$(manifest restore_to "$name")"
        if [ -n "$dest_override" ]; then
            dest="$dest_override"
        elif [ -n "$restore_to" ]; then
            dest="$REPO_ROOT/$restore_to"
        else
            dest="$REPO_ROOT/bundles-restored/$name"
        fi
        mkdir -p "$dest"
        zstd -dc "$STAGE/$path" | tar -xf - -C "$dest"
        note "→ $dest"
        hint="$(manifest restore_hint "$name")"
        [ -n "$hint" ] && note "   $hint"
    fi
done

[ "$fail" = 0 ] || die "有 bundle 校验失败"
echo "全部完成。"
