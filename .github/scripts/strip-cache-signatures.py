"""把 Gradle 缓存里带签名的 forge jar 就地剥掉签名。

CI 上 moddev 产出的 forge 构件带 META-INF/*.SF|*.RSA，而 JVM 会对它做 jar 摘要校验；
一旦构件与签名不匹配（不同 runner、不同缓存状态），任何加载 net.minecraftforge.* 的 JVM
都会抛 "SHA-384 digest error" —— 编译期代码生成和 JUnit 都栽在这上面。

只处理 forge 相关 jar，且只删除签名条目，不改任何类。
"""
import glob
import os
import re
import shutil
import sys
import tempfile
import zipfile

SIGNATURE = re.compile(r"^META-INF/[^/]+\.(SF|RSA|DSA)$", re.IGNORECASE)
ROOTS = [
    os.path.expanduser("~/.gradle/caches/modules-2"),
    os.path.join("build", "moddev", "artifacts"),
]


def strip(path):
    with zipfile.ZipFile(path) as source:
        if not any(SIGNATURE.match(info.filename) for info in source.infolist()):
            return False
        handle, temporary = tempfile.mkstemp(suffix=".jar", dir=os.path.dirname(path))
        os.close(handle)
        try:
            with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
                for info in source.infolist():
                    if SIGNATURE.match(info.filename):
                        continue
                    target.writestr(info, source.read(info.filename))
            shutil.move(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.remove(temporary)
        return True


def main():
    changed = 0
    candidates = []
    for root in ROOTS:
        candidates.extend(glob.glob(os.path.join(root, "**", "*.jar"), recursive=True))
    for path in candidates:
        name = os.path.basename(path)
        if "forge" not in name and "minecraft" not in name:
            continue
        try:
            if strip(path):
                changed += 1
                print("stripped", path)
        except Exception as error:
            print("skipped", path, error, file=sys.stderr)
    print(f"stripped {changed} jar(s)")


if __name__ == "__main__":
    main()
