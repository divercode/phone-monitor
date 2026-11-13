"""轮询 GitHub Actions：等待指定 commit 的 workflow 运行结束并打印每个 step 结果。

用法: python .tools/wait_actions.py <sha> [timeout_seconds]
"""
import json
import sys
import time
import urllib.error
import urllib.request

REPO = "divercode/phone-monitor"
UA = {"Accept": "application/vnd.github+json", "User-Agent": "phonemonitor-wait"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, None


def find_run(sha):
    st, runs = get(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=10")
    if st != 200 or not runs:
        return None
    for r in runs.get("workflow_runs", []):
        if (r.get("head_sha") or "").startswith(sha):
            return r
    return None


def main():
    sha = sys.argv[1] if len(sys.argv) > 1 else ""
    timeout = int(sys.argv[2]) if len(sys.argv) > 2 else 600
    started = time.time()
    last = None
    while time.time() - started < timeout:
        run = find_run(sha)
        if run:
            state = (run["status"], run.get("conclusion"))
            if state != last:
                print(f"[{int(time.time()-started)}s] run {run['id']} -> {state}")
                last = state
            if run["status"] == "completed":
                st, jobs = get(f"https://api.github.com/repos/{REPO}/actions/runs/{run['id']}/jobs")
                if st == 200:
                    for job in jobs.get("jobs", []):
                        print("job:", job["name"], job.get("conclusion"))
                        for step in job.get("steps", []):
                            if step.get("conclusion") not in (None, "success", "skipped"):
                                print("  FAILED STEP:", step.get("name"), "->", step.get("conclusion"))
                print("RESULT:", run.get("conclusion"))
                return 0 if run.get("conclusion") == "success" else 1
        time.sleep(12)
    print("等待超时，运行可能仍在进行")
    return 2


if __name__ == "__main__":
    sys.exit(main())
