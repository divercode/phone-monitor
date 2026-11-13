"""用 dulwich 把当前工程推送到 GitHub（本机没有 git 时的替代方案）。

用法：
  # SSH（推荐）：先在 GitHub 添加 .tools/keys/phonemonitor_ed25519.pub
  python .tools/push_to_github.py --repo git@github.com:<user>/<repo>.git

  # HTTPS + PAT：token 只放环境变量，不写进命令行历史
  $env:PM_TOKEN='github_pat_xxx'
  python .tools/push_to_github.py --repo https://github.com/<user>/<repo>.git

可选：--author "Name <email>" 指定提交者（默认 PhoneMonitor Builder <noreply@users.noreply.github.com>）。
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "pylibs"))

from dulwich import porcelain  # noqa: E402
from dulwich.repo import Repo  # noqa: E402

ROOT = os.path.dirname(HERE)
EXCLUDE_DIRS = {
    ".git", "build", ".gradle", ".idea",
    "pylibs", "tmp", "keys",
    "verify-clone", "ci-logs-view", "ci-logs", "ref",
}
EXCLUDE_FILES = {".DS_Store"}


def collect_files():
    result = []
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        for n in names:
            if n in EXCLUDE_FILES:
                continue
            path = os.path.join(base, n)
            result.append(os.path.relpath(path, ROOT).replace("\\", "/"))
    return sorted(result)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="远端仓库地址（ssh 或 https）")
    ap.add_argument("--author", default="PhoneMonitor Builder <noreply@users.noreply.github.com>")
    ap.add_argument("--message", default="feat: PhoneMonitor 安卓 UVC 监看 App（LUT/峰值/斑马/网格/直方图）")
    ap.add_argument("--no-push", action="store_true", help="只做 init/add/commit，不推送")
    args = ap.parse_args()

    repo_path = os.path.join(ROOT, ".git")
    if os.path.isdir(repo_path):
        repo = Repo(ROOT)
        print("复用已有仓库")
    else:
        repo = Repo.init(ROOT)
        print("初始化仓库:", repo_path)

    files = collect_files()
    print(f"待提交文件 {len(files)} 个")

    # 先把磁盘上已不存在的文件从索引里移除（dulwich 的 add 不会处理删除）
    index = repo.open_index()
    missing = [p for p in list(index) if not os.path.exists(os.path.join(ROOT, p.decode()))]
    if missing:
        for p in missing:
            del index[p]
        index.write()
        print(f"已从索引移除 {len(missing)} 个已删除文件")

    porcelain.add(repo, paths=files)

    author = args.author.encode()
    try:
        porcelain.commit(
            repo,
            message=args.message.encode(),
            author=author,
            committer=author,
        )
        print("提交完成")
    except Exception as e:  # 没有变更时也会走到这里
        print("提交跳过:", type(e).__name__, e)

    kwargs = {}
    token = os.environ.get("PM_TOKEN")
    if token:
        kwargs["username"] = token
        kwargs["password"] = ""
    else:
        key = os.path.join(HERE, "keys", "phonemonitor_ed25519")
        known = os.path.join(HERE, "known_hosts")
        if os.path.exists(key):
            kwargs["key_filename"] = key
            kwargs["ssh_command"] = (
                f'ssh -i "{key}" -o StrictHostKeyChecking=no '
                f'-o UserKnownHostsFile="{known}"'
            )

    print("推送 ->", args.repo)
    if args.no_push:
        print("已跳过推送（--no-push）")
        return
    # 本地分支可能是 master（dulwich 默认）或 main，统一推到远端 main
    heads = [b"refs/heads/main", b"refs/heads/master"]
    local = next((h for h in heads if h in repo.refs), heads[1])
    refspec = local + b":refs/heads/main"
    print("refspec:", refspec.decode())
    try:
        porcelain.push(
            repo,
            args.repo,
            refspecs=[refspec],
            **kwargs,
        )
    except Exception as e:
        print("推送失败:", type(e).__name__, e)
        sys.exit(1)
    print("推送成功")


if __name__ == "__main__":
    main()
