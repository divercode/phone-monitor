"""下载参考实现源码到 .tools/ref/，便于离线检索协议常量。

用法: python .tools/fetch_ref.py
"""
import os
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "ref")
BASE = "https://raw.githubusercontent.com/nredd/tethercam/main/"

FILES = [
    "docs/usb.md",
    "src/sony/codes.rs",
    "src/sony/live_view.rs",
    "src/sony/protocol.rs",
    "src/sony/camera.rs",
    "src/ptp/codes.rs",
    "src/ptp/container.rs",
    "src/ptp/session.rs",
    "src/sony/quirks.rs",
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for rel in FILES:
        url = BASE + rel
        target = os.path.join(OUT, rel.replace("/", "_"))
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                data = r.read()
            with open(target, "wb") as fh:
                fh.write(data)
            print(f"OK {rel} ({len(data)} bytes)")
        except Exception as e:  # noqa: BLE001
            print(f"FAIL {rel}: {type(e).__name__} {e}")


if __name__ == "__main__":
    main()
