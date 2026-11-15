#!/usr/bin/env bash
# 取回 resources 分支上的归档资源，并逐个校验 sha256。
#
#   ./scripts/fetch-resources.sh --list               列出所有 bundle
#   ./scripts/fetch-resources.sh renders              取回一份
#   ./scripts/fetch-resources.sh renders tooling      取回多份
#   ./scripts/fetch-resources.sh --all                全部取回
#   ./scripts/fetch-resources.sh --to /tmp/x renders  换个落点（不按 MANIFEST 的默认位置）
#   ./scripts/fetch-resources.sh --verify renders     只校验已有副本
#
# 默认落点见 MANIFEST.json 的 restore_to（相对仓库根）。
# 环境变量：KINETIC_RES_BRANCH（默认 resources）、KINETIC_RES_REMOTE（默认 origin）。
set -euo pipefail

BRANCH="${KINETIC_RES_BRANCH:-resources}"
REMOTE="${KINETIC_RES_REMOTE:-origin}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

die() { echo "错误：$*" >&2; exit 1; }

usage() { sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; }

TARGET=""
VERIFY_ONLY=0
ALL=0
LIST=0
NAMES=()

while [ $# -gt 0 ]; do
    case "$1" in
        --list) LIST=1; shift ;;
        --all) ALL=1; shift ;;
        --verify) VERIFY_ONLY=1; shift ;;
        --to) [ $# -ge 2 ] || die "--to 需要一个目录"; TARGET="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        -*) die "未知选项 $1（--help 查看用法）" ;;
        *) NAMES+=("$1"); shift ;;
    esac
done

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
MANIFEST="$STAGE/MANIFEST.json"
TREE=""

# ---- MANIFEST 查询 ----------------------------------------------------------
# q <field> <bundle>；特殊：q table / q names / q total <field>
q() {
    python3 - "$MANIFEST" "$@" <<'PY'
import json, sys
path, field, rest = sys.argv[1], sys.argv[2], sys.argv[3:]
m = json.load(open(path, encoding="utf-8"))

def width(text):
    return sum(2 if ord(c) > 0x1100 else 1 for c in text)

if field == "table":
    rows = [(b["name"], b["kind"], f"{b['bytes_archived'] / 1048576:.1f}M", b["title"]) for b in m["bundles"]]
    heads = ("NAME", "KIND", "归档", "说明")
    cols = [max(width(r[i]) for r in rows + [heads]) for i in range(4)]
    pad = lambda t, w: t + " " * (w - width(t))
    for i, row in enumerate([heads] + rows):
        cells = [pad(row[0], cols[0]), pad(row[1], cols[1]),
                 " " * (cols[2] - width(row[2])) + row[2], row[3]]
        print(f"{cells[0]}  {cells[1]}  {cells[2]}  {cells[3]}")
        if i == 0:
            print("-" * (sum(cols) + 6))
    t = m["totals"]
    print(f"\n共 {t['bundles']} 份：原始 {t['bytes_raw'] / 1048576:.1f} MiB，归档 {t['bytes_archived'] / 1048576:.1f} MiB")
    sys.exit(0)

if field == "names":
    for b in m["bundles"]:
        print(b["name"])
    sys.exit(0)

if field == "total":
    print(m["totals"].get(rest[0], ""))
    sys.exit(0)

name = rest[0]
for b in m["bundles"]:
    if b["name"] == name:
        value = b.get(field)
        print("" if value is None else value)
        break
else:
    sys.exit(f"MANIFEST 里没有 bundle：{name}")
PY
}

# ---- 取回分支并导出 MANIFEST ------------------------------------------------
fetch_tree() {
    echo "拉取 $REMOTE/$BRANCH ..." >&2
    git fetch --no-tags "$REMOTE" "+refs/heads/$BRANCH:refs/remotes/$REMOTE/$BRANCH" >/dev/null 2>&1 \
        || die "拉取失败：检查网络与凭据（本机可先启动 tools/gh-tunnel.py 并设置 HTTPS_PROXY）"
    TREE="$REMOTE/$BRANCH"
    git archive "$TREE" -- MANIFEST.json | tar -xf - -C "$STAGE" || die "取不到 MANIFEST.json"
}

export_path() { # <path> <dest>
    git archive "$TREE" -- "$1" | tar -xf - -C "$2" || die "导出失败：$1"
}

# ---- 校验 -------------------------------------------------------------------
verify_dir() { # <dir> <bundle>
    python3 - "$MANIFEST" "$1" "$2" <<'PY'
import hashlib, json, os, sys
manifest, root, name = sys.argv[1], sys.argv[2], sys.argv[3]
entry = next(b for b in json.load(open(manifest, encoding="utf-8"))["bundles"] if b["name"] == name)
bad = missing = 0
for f in entry["files"]:
    p = os.path.join(root, f["path"])
    if not os.path.isfile(p):
        missing += 1
        print(f"    缺失 {f['path']}")
        continue
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    if h.hexdigest() != f["sha256"] or os.path.getsize(p) != f["bytes"]:
        bad += 1
        print(f"    不匹配 {f['path']}")
print(f"    {entry['file_count']} 个文件：缺失 {missing}，损坏 {bad}")
sys.exit(1 if (bad or missing) else 0)
PY
}

verify_archive() { # <archive> <bundle>
    local actual expected
    actual="$(sha256sum "$1" | cut -d' ' -f1)"
    expected="$(q sha256 "$2")"
    if [ "$actual" != "$expected" ]; then
        echo "    sha256 不符"
        echo "      期望 $expected"
        echo "      实际 $actual"
        return 1
    fi
    echo "    sha256 $actual"
}

# ---- 主流程 -----------------------------------------------------------------
if [ "$VERIFY_ONLY" = 1 ] && [ -f MANIFEST.json ]; then
    cp MANIFEST.json "$MANIFEST"        # 只校验时优先用本地清单，可离线
else
    fetch_tree
fi

if [ "$LIST" = 1 ]; then
    q table
    exit 0
fi

if [ "$ALL" = 1 ]; then
    while read -r n; do NAMES+=("$n"); done < <(q names)
fi
[ ${#NAMES[@]} -gt 0 ] || die "没有指定 bundle（--list 查看清单，或 --all）"

fail=0
for name in "${NAMES[@]}"; do
    kind="$(q kind "$name")"
    path="$(q path "$name")"
    echo "== $name ($kind)"

    if [ "$VERIFY_ONLY" = 1 ]; then
        if [ "$kind" = dir ]; then
            dest="${TARGET:-$REPO_ROOT/$(q restore_to "$name")}"
            [ -n "$TARGET" ] && dest="$TARGET/$name"
            echo "    校验 $dest"
            verify_dir "$dest" "$name" || fail=1
        elif [ -f "$path" ]; then
            verify_archive "$path" "$name" || fail=1
        else
            echo "    跳过：$path 不在本地（归档型只校验压缩包本身）"
        fi
        continue
    fi

    [ -n "$TREE" ] || fetch_tree
    export_path "$path" "$STAGE"

    if [ "$kind" = dir ]; then
        verify_dir "$STAGE/$path" "$name" || fail=1
        dest="${TARGET:-$REPO_ROOT/$(q restore_to "$name")}"
        [ -n "$TARGET" ] && dest="$TARGET/$name"
        mkdir -p "$dest"
        cp -a "$STAGE/$path/." "$dest/"
        echo "  → $dest"
    else
        verify_archive "$STAGE/$path" "$name" || fail=1
        restore_to="$(q restore_to "$name")"
        if [ -n "$TARGET" ]; then
            dest="$TARGET"
        elif [ -n "$restore_to" ]; then
            dest="$REPO_ROOT/$restore_to"
        else
            dest="$REPO_ROOT/bundles-restored/$name"
        fi
        mkdir -p "$dest"
        zstd -dc "$STAGE/$path" | tar -xf - -C "$dest"
        echo "  → $dest"
        hint="$(q restore_hint "$name")"
        [ -n "$hint" ] && echo "    $hint"
    fi
done

[ "$fail" = 0 ] || die "有 bundle 校验失败"
echo "全部完成。"
