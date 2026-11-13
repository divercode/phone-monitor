"""拉取 GitHub Actions 最近一次运行的失败日志（公开仓库，无需 token）。"""
import json
import sys
import urllib.error
import urllib.request

REPO = "divercode/phone-monitor"
UA = {"Accept": "application/vnd.github+json", "User-Agent": "phonemonitor-check"}


def get_json(url):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, None


def get_text(url):
    req = urllib.request.Request(url, headers={"User-Agent": "phonemonitor-check"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
            return r.status, data.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception as e:  # noqa: BLE001
        return -1, f"{type(e).__name__}: {e}"


def main():
    st, runs = get_json(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=1")
    if st != 200 or not runs.get("workflow_runs"):
        print("读不到 run:", st)
        return 1
    run = runs["workflow_runs"][0]
    print("run:", run["id"], run["status"], run.get("conclusion"), (run.get("head_sha") or "")[:8])

    st, jobs = get_json(f"https://api.github.com/repos/{REPO}/actions/runs/{run['id']}/jobs")
    if st != 200:
        print("读不到 jobs:", st)
        return 1
    for job in jobs.get("jobs", []):
        print("job:", job["id"], job["name"], job["status"], job.get("conclusion"))
        st, log = get_text(
            f"https://api.github.com/repos/{REPO}/actions/jobs/{job['id']}/logs"
        )
        print("日志状态:", st, "长度:", len(log))
        if st == 200 and log:
            lines = log.splitlines()
            print("---- 日志末尾 120 行 ----")
            for line in lines[-120:]:
                print(line)
        else:
            print("日志不可匿名获取，需另行处理")
    return 0


if __name__ == "__main__":
    sys.exit(main())
