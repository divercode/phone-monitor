"""在没有 pip 可用的情况下，直接下载 wheel 并解包安装纯 Python 依赖。

用法： python .tools/install_wheels.py
安装到 .tools/pylibs，然后可用 sys.path.insert 使用。
"""
import json
import os
import sys
import urllib.request
import zipfile

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pylibs")
PKGS = ["dulwich", "urllib3", "certifi"]


def best_wheel(files):
    """优先 cp312-win_amd64，其次 py3-none-any。"""
    wheels = [f for f in files if f["filename"].endswith(".whl")]
    tags = ["cp312-cp312-win_amd64", "cp312-abi3-win_amd64", "py3-none-any", "py2.py3-none-any"]
    for tag in tags:
        for w in wheels:
            if tag in w["filename"]:
                return w
    # 退而求其次：任意 win_amd64 或纯 Python wheel
    for w in wheels:
        if "win_amd64" in w["filename"] or "none-any" in w["filename"]:
            return w
    return None


def main():
    os.makedirs(BASE, exist_ok=True)
    for pkg in PKGS:
        url = f"https://pypi.org/pypi/{pkg}/json"
        with urllib.request.urlopen(url, timeout=30) as resp:
            data = json.load(resp)
        wheel = best_wheel(data["urls"])
        if wheel is None:
            print(f"{pkg}: 没找到可用 wheel")
            sys.exit(1)
        target = os.path.join(BASE, wheel["filename"])
        print(f"下载 {pkg}: {wheel['filename']}")
        with urllib.request.urlopen(wheel["url"], timeout=60) as resp, open(target, "wb") as fh:
            fh.write(resp.read())
        with zipfile.ZipFile(target) as zf:
            zf.extractall(BASE)
        os.remove(target)
    print("安装完成 ->", BASE)


if __name__ == "__main__":
    main()
