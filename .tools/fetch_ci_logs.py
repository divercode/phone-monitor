"""拉取 ci-logs 分支并打印构建/测试日志（失败时由 GitHub Actions 自动推送）。"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "pylibs"))

from dulwich import porcelain  # noqa: E402

URL = "git@github.com:divercode/phone-monitor.git"
TARGET = os.path.join(HERE, "ci-logs-view")
KEY = os.path.join(HERE, "keys", "phonemonitor_ed25519")
KNOWN = os.path.join(HERE, "known_hosts")
SSH_CMD = f'ssh -i "{KEY}" -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile="{KNOWN}"'


def main():
    if os.path.isdir(TARGET):
        repo = porcelain.pull(TARGET, URL, refspecs=[b"refs/heads/ci-logs:refs/heads/ci-logs"],
                              ssh_command=SSH_CMD, key_filename=KEY)
        print("已更新本地 ci-logs")
    else:
        porcelain.clone(URL, TARGET, branch=b"ci-logs", ssh_command=SSH_CMD, key_filename=KEY)
        print("已克隆 ci-logs")

    for name in os.listdir(os.path.join(TARGET, "ci-logs")):
        path = os.path.join(TARGET, "ci-logs", name)
        print("=" * 20, name, os.path.getsize(path), "bytes", "=" * 20)
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
        for line in lines[-120:]:
            print(line.rstrip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
