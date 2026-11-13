"""抓取网页并输出纯文本摘要（本机 shell 的 schannel TLS 不可用，用 Python 的 OpenSSL 直连）。

用法: python .tools/fetch_page.py <url> [max_chars]
"""
import re
import sys
import urllib.request

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/126.0 Safari/537.36",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}


def main():
    url = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 4000
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            html = r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        print("FETCH_ERROR:", type(e).__name__, e)
        return 1

    html = re.sub(r"(?is)<(script|style|noscript).*?</\1>", " ", html)
    text = re.sub(r"(?s)<[^>]+>", " ", html)
    text = re.sub(r"&nbsp;", " ", text)
    text = re.sub(r"&amp;", "&", text)
    text = re.sub(r"&lt;", "<", text)
    text = re.sub(r"&gt;", ">", text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n", text).strip()
    print(text[:limit])
    return 0


if __name__ == "__main__":
    sys.exit(main())
