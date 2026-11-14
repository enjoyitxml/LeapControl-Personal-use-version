#!/usr/bin/env python3
"""把当前提交发布成「个人学习版」，推到公开仓库。

## 为什么需要它
2026-11-15 起本项目同时维护两条线：自用版（私有仓库）与个人学习版（公开仓库）。
两版**共用同一份源码**，差异只有 [Edition.kt] 里那个构建开关，版本号天然同步。

于是「发布个人学习版」不需要第二份代码库，只需要把当前提交做几处**确定的替换**再推上去：

  1. `gradle.properties`：写入 `leapcontrol.edition=personal`，并删掉内置的 GitHub 令牌密文
     （公开仓库绝不能带能读私有仓库的凭据）；
  2. `README.md`：换成 `docs/public/README.md`（面向外部读者）；
  3. 六份小组件布局：`android:src` 的零跑 V 标换成方向盘、设计态标题换成「我的爱车」
     —— 桌面小组件的**预览图**是按布局静态渲染的，只改运行时不改布局的话，
     添加小组件时的那张预览图上仍然是零跑 logo；
  4. 删掉 `docs/screenshots/`：那几张截图拍的是自用版界面，带着官方 logo。

## 为什么在临时目录里构建
公开版的包必须**从替换后的树**构建，否则包里的预览图还是零跑标。
所以这里是「生成提交 → git archive 导出 → 在导出目录构建」，不碰工作区。
构建完成后工作区仍然干净，自用版随时可以照常发版。

## 用法
    export GH_TOKEN=ghp_xxxx
    python3 scripts/publish-personal.py                  # 构建 + 推送 + 发 Release
    python3 scripts/publish-personal.py --dry-run        # 只打印会推什么
    python3 scripts/publish-personal.py --no-build       # 用已有 APK
    python3 scripts/publish-personal.py --no-release     # 只推代码，不发 Release

首次运行前需要先建好空仓库（脚本会提示），或直接：
    python3 scripts/publish-personal.py --create-repo
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

PUBLIC_REPO = os.environ.get("PUBLIC_REPO", "enjoyitxml/LeapControl-Personal-use-version")
BRANCH = "main"
# 本机只有 SOCKS 7897 能到 github.com（直连被墙、HTTP 代理 502）
SOCKS = "socks5h://127.0.0.1:7897"

WIDGET_LAYOUTS = [
    "app/src/main/res/layout/widget_a.xml",
    "app/src/main/res/layout/widget_a_wide.xml",
    "app/src/main/res/layout/widget_d.xml",
    "app/src/main/res/layout/widget_d_wide.xml",
    "app/src/main/res/layout/widget_t.xml",
    "app/src/main/res/layout/widget_t_wide.xml",
]


class Fail(Exception):
    pass


def run(args: list[str], cwd: str | None = None, env: dict | None = None,
        check: bool = True) -> subprocess.CompletedProcess:
    out = subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True)
    if check and out.returncode != 0:
        raise Fail(f"{' '.join(args)}\n{out.stderr.strip()}")
    return out


def git(*args: str, cwd: str, env: dict | None = None, check: bool = True) -> str:
    return run(["git", *args], cwd=cwd, env=env, check=check).stdout


def net_git_args() -> list[str]:
    """走 SOCKS 的 git 网络参数；端口不在时退回系统代理。

    注意 `-c` 与 `name=value` 必须是**两个参数**（写成 `-chttp.proxy=…` git 会当未知选项）。
    """
    import socket

    try:
        with socket.create_connection(("127.0.0.1", 7897), timeout=0.6):
            return ["-c", f"http.proxy={SOCKS}", "-c", "http.version=HTTP/1.1"]
    except OSError:
        return []


def token() -> str:
    t = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if t:
        return t.strip()
    out = run(["security", "find-internet-password", "-s", "github.com", "-w"], check=False)
    return out.stdout.strip()


def api(method: str, path: str, tok: str, payload: dict | None = None,
        tolerate: tuple[int, ...] = ()) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {tok}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    # 显式不走系统代理：本机 HTTP 代理对 github 会 502，api.github.com 直连反而通
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=120) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        if e.code in tolerate:
            return {}
        raise Fail(f"HTTP {e.code} {method} {path}: {e.read().decode()[:200]}")


def version_of(root: str) -> str:
    src = open(os.path.join(root, "app/build.gradle.kts"), encoding="utf-8").read()
    m = re.search(r'versionName = "([^"]+)"', src)
    if not m:
        raise Fail("读不到 versionName")
    return m.group(1)


def changelog_body(root: str, version: str) -> str:
    text = open(os.path.join(root, "CHANGELOG.md"), encoding="utf-8").read()
    m = re.search(rf"## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|\Z)", text, re.S)
    return (m.group(1).strip() if m else "- 与自用版同步")


def public_readme(root: str, version: str) -> str:
    return open(os.path.join(root, "docs/public/README.md"), encoding="utf-8").read()


def public_notes(root: str, version: str) -> str:
    tpl = open(os.path.join(root, "docs/public/release-notes.md"), encoding="utf-8").read()
    return tpl.replace("{{VERSION}}", version).replace(
        "{{CHANGELOG}}", changelog_body(root, version))


def patched_widget_layout(src: str) -> str:
    out = src.replace('android:src="@drawable/ic_leap_logo"',
                      'android:src="@drawable/ic_widget_logo_steering"')
    out = out.replace('android:text="我的零跑"', 'android:text="我的爱车"')
    if out == src:
        raise Fail("小组件布局里没有找到车标 / 设计态标题，替换规则可能过时了")
    return out


def public_gradle_properties(src: str) -> str:
    """写入 edition=personal，并整块删掉内置令牌（含注释）。"""
    # 令牌那一段在 gradle.properties 里用显式标记包着，这里按标记整块删 ——
    # 按关键字过滤容易漏掉半句注释，公开仓库里留一段「下面是一段密文」很怪。
    src = re.sub(r"# >>> PRIVATE-ONLY-BEGIN.*?# <<< PRIVATE-ONLY-END\n?", "", src, flags=re.S)
    if "leapcontrol.gh.token" in src:
        raise Fail("gradle.properties 里还有令牌键，标记块没覆盖到？")
    lines = [
        ln for ln in src.splitlines()
        if not ln.strip().startswith("leapcontrol.edition")
    ]
    while lines and not lines[-1].strip():
        lines.pop()
    lines.append("")
    lines.append("# 个人学习版：与自用版共用同一份源码，差异由这个开关驱动（见 Edition.kt）")
    lines.append("leapcontrol.edition=personal")
    return "\n".join(lines) + "\n"


def patched_manifest(src: str) -> str:
    """把停用中的「官方 logo」alias 的图标换成方向盘那一套。

    alias 本身是关着的（个人学习版只启用方向盘），但它的 `android:icon` 会把
    `@mipmap/ic_launcher_official*` 钉成「被引用」，资源压缩就不敢删 ——
    结果公开包里仍然躺着零跑官方图标。换成方向盘那两张之后，官方图标没有任何引用，
    构建时会被 `shrinkResources` 一并剔掉（2026-11-15 实测：改完 APK 里只剩方向盘）。
    """
    out = src.replace('android:icon="@mipmap/ic_launcher_official"',
                      'android:icon="@mipmap/ic_launcher"')
    out = out.replace('android:roundIcon="@mipmap/ic_launcher_official_round"',
                      'android:roundIcon="@mipmap/ic_launcher_round"')
    if out == src:
        raise Fail("清单里的官方图标引用没找到，替换规则可能过时了")
    return out


def build_tree(root: str, tmp: str) -> str:
    """用临时 index 组装公开版 tree，返回 tree sha。工作区不受影响。"""
    index = os.path.join(tmp, "index")
    env = dict(os.environ, GIT_INDEX_FILE=index)
    git("read-tree", "HEAD", cwd=root, env=env)

    def put(path: str, text: str) -> None:
        f = os.path.join(tmp, "blob")
        with open(f, "w", encoding="utf-8") as fh:
            fh.write(text)
        sha = git("hash-object", "-w", f, cwd=root).strip()
        git("update-index", "--add", "--cacheinfo", f"100644,{sha},{path}", cwd=root, env=env)

    put("gradle.properties", public_gradle_properties(
        open(os.path.join(root, "gradle.properties"), encoding="utf-8").read()))
    put("README.md", public_readme(root, version_of(root)))
    put("app/src/main/AndroidManifest.xml", patched_manifest(
        open(os.path.join(root, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()))
    for rel in WIDGET_LAYOUTS:
        put(rel, patched_widget_layout(open(os.path.join(root, rel), encoding="utf-8").read()))

    # 截图拍的是自用版界面，带着官方 logo
    shots = run(["git", "ls-files", "docs/screenshots"], cwd=root).stdout.split()
    for rel in shots:
        git("update-index", "--force-remove", rel, cwd=root, env=env, check=False)

    return git("write-tree", cwd=root, env=env).strip()


def remote_head(tok: str) -> str:
    args = ["git", *net_git_args(), "ls-remote",
            f"https://github.com/{PUBLIC_REPO}.git", f"refs/heads/{BRANCH}"]
    env = {k: v for k, v in os.environ.items()
           if k.lower() not in {"http_proxy", "https_proxy", "all_proxy"}}
    out = run(args, env=env, check=False)
    if out.returncode != 0:
        return ""
    line = out.stdout.strip().splitlines()
    return line[0].split()[0] if line else ""


def main() -> int:
    ap = argparse.ArgumentParser(description="发布个人学习版到公开仓库")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--dry-run", action="store_true", help="只组装 tree，不推送")
    ap.add_argument("--no-build", action="store_true", help="跳过构建（复用已有 APK）")
    ap.add_argument("--no-release", action="store_true", help="只推代码，不建 Release")
    ap.add_argument("--build-only", action="store_true",
                    help="只按公开版源码构建，不推送（用来先检查 APK 里的资源）")
    ap.add_argument("--create-repo", action="store_true", help="仓库不存在时先创建（公开）")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    tok = token()
    if not tok:
        print("❌ 缺少令牌：export GH_TOKEN=ghp_xxxx", file=sys.stderr)
        return 1
    if git("status", "--porcelain", cwd=root).strip():
        print("❌ 工作区有未提交的改动，先提交再发布", file=sys.stderr)
        return 1

    version = version_of(root)
    print(f"==> 目标仓库 {PUBLIC_REPO}（公开）  版本 v{version}")

    with tempfile.TemporaryDirectory(prefix="lc-public-") as tmp:
        tree = build_tree(root, tmp)
        parent = remote_head(tok)
        message = f"release: v{version} 个人学习版\n\n由 publish-personal.py 从自用版同版本源码生成。"
        cmd = ["commit-tree", tree, "-m", message]
        if parent:
            cmd += ["-p", parent]
        commit = git(*cmd, cwd=root).strip()
        print(f"==> 提交 {commit[:8]}（parent={parent[:8] if parent else '无（首次）'}）")

        if args.dry_run:
            print("（dry-run，未推送）")
            return 0

        # ---- 构建：必须在「替换后的树」里构建，否则小组件预览图还是零跑标 ----
        apk_src = os.path.join(root, "app/build/outputs/apk/release",
                               f"LeapControl-{version}-personal.apk")
        if not args.no_build:
            build_dir = os.path.join(tmp, "src")
            os.makedirs(build_dir, exist_ok=True)
            # 走二进制管道：git archive 出来的是 tar，按文本解码会毁掉里面的二进制资源
            arch = subprocess.run(["git", "archive", commit], cwd=root, capture_output=True)
            if arch.returncode != 0:
                raise Fail(f"git archive 失败：{arch.stderr.decode()[:200]}")
            p = subprocess.run(["tar", "-x", "-C", build_dir], input=arch.stdout,
                               capture_output=True)
            if p.returncode != 0:
                raise Fail(f"解包失败：{p.stderr.decode()[:200]}")
            # 本机 SDK 路径不进仓库
            local = os.path.join(root, "local.properties")
            if os.path.exists(local):
                shutil.copy2(local, os.path.join(build_dir, "local.properties"))
            java_home = run(["/usr/libexec/java_home", "-v", "21"]).stdout.strip()
            print("==> 在公开版源码里构建 release（约 4~5 分钟）…")
            run(["./gradlew", ":app:assembleRelease", "--no-daemon", "-q"],
                cwd=build_dir, env=dict(os.environ, JAVA_HOME=java_home))
            built = os.path.join(build_dir, "app/build/outputs/apk/release",
                                 f"LeapControl-{version}-personal.apk")
            if not os.path.exists(built):
                raise Fail("构建完成但没找到 APK")
            os.makedirs(os.path.dirname(apk_src), exist_ok=True)
            shutil.copy2(built, apk_src)
        if not os.path.exists(apk_src):
            raise Fail(f"没有可用的 APK：{apk_src}")

        if args.build_only:
            print(f"ℹ️  只构建，未推送：{apk_src}")
            return 0

        # ---- 推送 ----
        env = {k: v for k, v in os.environ.items()
               if k.lower() not in {"http_proxy", "https_proxy", "all_proxy"}}
        url = f"https://github.com/{PUBLIC_REPO}.git"
        cred = f"!f(){{ echo username=enjoyitxml; echo password={tok}; }}; f"
        net = net_git_args()
        push = run(["git", *net, "-c", f"credential.helper={cred}",
                    "push", "--force", url,
                    f"{commit}:refs/heads/{BRANCH}"], env=env, check=False)
        if push.returncode != 0:
            if args.create_repo and "not found" in (push.stderr or "").lower():
                print("==> 仓库不存在，先创建…")
                api("POST", "/user/repos", tok, {
                    "name": PUBLIC_REPO.split("/")[-1],
                    "private": False,
                    "description": "零控个人学习版：第三方控车客户端，仅限个人学习研究使用",
                    "has_issues": True,
                    "has_wiki": False,
                })
                push = run(["git", *net, "-c", f"credential.helper={cred}",
                            "push", "--force", url,
                            f"{commit}:refs/heads/{BRANCH}"], env=env, check=False)
            if push.returncode != 0:
                raise Fail(f"推送失败：{push.stderr.strip()[:400]}")
        print(f"✅ 已推送 {PUBLIC_REPO}@{BRANCH}")

        run(["git", *net, "-c", f"credential.helper={cred}", "push", "--force", url,
             f"{commit}:refs/tags/v{version}"], env=env, check=False)

        if args.no_release:
            print("ℹ️  跳过 Release")
            return 0

        # ---- Release ----
        notes = public_notes(root, version)
        notes_file = os.path.join(tmp, "notes.md")
        with open(notes_file, "w", encoding="utf-8") as fh:
            fh.write(notes)
        asset_dir = os.path.join(root, "release/personal")
        os.makedirs(asset_dir, exist_ok=True)
        asset = os.path.join(asset_dir, f"LeapControl-v{version}.apk")
        shutil.copy2(apk_src, asset)
        env2 = dict(env, GH_TOKEN=tok)
        out = run([sys.executable, "scripts/gh-release.py", f"v{version}", asset,
                   "--notes-file", notes_file, "--repo", PUBLIC_REPO],
                  cwd=root, env=env2, check=False)
        print(out.stdout.strip() or out.stderr.strip())
        if out.returncode != 0:
            raise Fail("Release 创建失败")
        print(f"✅ 完成：https://github.com/{PUBLIC_REPO}/releases/tag/v{version}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Fail as e:
        print(f"❌ {e}", file=sys.stderr)
        sys.exit(1)
