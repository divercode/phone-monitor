"""递增 app/build.gradle.kts 里的版本号。

用法:
  python .tools/bump_version.py            # patch +1（修复）
  python .tools/bump_version.py --minor    # minor +1, patch 归零（功能）
  python .tools/bump_version.py --major    # major +1（重大版本）

versionCode 总是 +1。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")


def main():
    text = open(GRADLE, encoding="utf-8").read()

    code_match = re.search(r"versionCode\s*=\s*(\d+)", text)
    name_match = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', text)
    if not code_match or not name_match:
        print("找不到 versionCode / versionName，放弃")
        return 1

    code = int(code_match.group(1)) + 1
    major, minor, patch = (int(name_match.group(i)) for i in (1, 2, 3))

    if "--major" in sys.argv:
        major, minor, patch = major + 1, 0, 0
    elif "--minor" in sys.argv:
        minor, patch = minor + 1, 0
    else:
        patch += 1

    new_name = f"{major}.{minor}.{patch}"
    text = text.replace(code_match.group(0), f"versionCode = {code}")
    text = text.replace(name_match.group(0), f'versionName = "{new_name}"')
    open(GRADLE, "w", encoding="utf-8", newline="\n").write(text)

    print(f"版本已更新: versionCode {code}, versionName {new_name}")
    print("别忘了同步 README「当前版本」并写提交说明")
    return 0


if __name__ == "__main__":
    sys.exit(main())
