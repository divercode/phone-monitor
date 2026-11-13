"""检查 GitHub 仓库可见性 / Actions 运行状态（无需 token，仅公开仓库可读）。"""
import json
import sys
import urllib.error
import urllib.request

REPO = "divercode/phone-monitor"
UA = {"Accept": "application/vnd.github+json", "User-Agent": "phonemonitor-check"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, None


def main():
    status, repo = get(f"https://api.github.com/repos/{REPO}")
    print("repo API:", status)
    if status != 200:
        print("私有仓库或无权限：未认证读不到 Actions 状态")
        return 0
    print("visibility:", repo.get("visibility"), "| default branch:", repo.get("default_branch"))

    status, runs = get(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=3")
    print("runs API:", status)
    if status != 200 or not runs:
        print("读不到 workflow run 列表（可能 Actions 未启用）")
        return 0

    items = runs.get("workflow_runs", [])
    if not items:
        print("还没有 workflow 运行记录（可能推送触发尚未开始，或 Actions 未启用）")
        return 0

    for r in items:
        print(
            "run {id} | {name} | status={st} conclusion={con} | branch={br} sha={sha}".format(
                id=r["id"], name=r.get("name"), st=r["status"], con=r.get("conclusion"),
                br=r.get("head_branch"), sha=(r.get("head_sha") or "")[:8],
            )
        )
        js = r.get("jobs_url")
        if js:
            st2, jobs = get(js)
            if st2 == 200 and jobs:
                for j in jobs.get("jobs", []):
                    print("   job:", j.get("name"), "| status=", j.get("status"),
                          "| conclusion=", j.get("conclusion"))
                    for step in j.get("steps", []):
                        print("      step:", step.get("name"), "->", step.get("conclusion"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
