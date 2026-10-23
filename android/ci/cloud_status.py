#!/usr/bin/env python3
"""Read a public CNB build by commit, without browser automation or credentials."""
import argparse
from html.parser import HTMLParser
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "https://cnb.cool/sizuku-2025/lumora"


class PageData(HTMLParser):
    def __init__(self):
        super().__init__()
        self.active = False
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.active = dict(attrs).get("id") == "__NEXT_DATA__"

    def handle_endtag(self, tag):
        if tag == "script":
            self.active = False

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)


def find_builds(value, sha):
    if isinstance(value, dict):
        if value.get("sha") == sha and value.get("sn") and "status" in value:
            yield value
        else:
            for child in value.values():
                yield from find_builds(child, sha)
    elif isinstance(value, list):
        for child in value:
            yield from find_builds(child, sha)


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--sha", help="Full commit SHA (defaults to HEAD)")
    options = args.parse_args()
    sha = options.sha or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        args.error("--sha must be a full 40-character commit SHA")
    request = urllib.request.Request(REPO + "/-/build/logs?sha=" + sha,
                                     headers={"User-Agent": "Lumora-CI-status/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            page = response.read().decode("utf-8")
        parser = PageData()
        parser.feed(page)
        data = json.loads("".join(parser.parts))
        builds = list(find_builds(data, sha))
        if not builds:
            print(json.dumps({"sha": sha, "status": "not_found", "url": REPO + "/-/build/logs"}))
            return 2
        build = max(builds, key=lambda b: b.get("createTime", ""))
        print(json.dumps({key: build.get(key) for key in
                          ["sha", "sn", "status", "duration", "buildLogUrl", "pipelineSuccessCount", "pipelineTotalCount"]},
                         ensure_ascii=False, indent=2))
        return 0 if build["status"] == "success" else 1 if build["status"] in ("error", "cancel") else 2
    except (urllib.error.URLError, ValueError) as error:
        print("Unable to verify CNB state: " + str(error), file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
