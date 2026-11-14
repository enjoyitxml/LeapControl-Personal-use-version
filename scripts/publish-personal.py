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


def public_notes(root: str, version: str) -> str:
    # 正文完全由 docs/public/release-notes.md 决定。
    # 首个公开版本（v2.4.1）用户明确要求不列更新条目，正文只有一句
    # 「个人学习版的第一个可用版本。」；以后发版直接在这个文件里写条目即可，
    # {{VERSION}} / {{CHANGELOG}} 两个占位符仍然可用。
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


# ---------------------------------------------------------------------------
# README 改写
# ---------------------------------------------------------------------------
# 公开仓库的 README 与自用版共用一份（用户要求两边保持一致），但里面有些话只对自用版成立：
# 包名不同、仓库地址不同、「请勿外传 / 请勿二次分发」放在公开仓库里自相矛盾、
# 内置令牌在公开版根本不存在。所以组装公开树时逐条改成公开版的说法。
#
# **哪条没命中就直接报错**：README 改了措辞之后，脚本必须当场喊出来，
# 否则公开仓库会悄悄带着自用版的说法发出去（这类漏改只有读者会发现）。
README_REWRITES: list[tuple[str, str]] = [
    (
        "零跑汽车第三方控车 Android 应用，包名 `com.leapcontrol.app`。\n",
        "零跑汽车第三方控车 Android 应用（个人学习研究版），包名 `com.leapcontrol.personal`，可与自用版同时安装。\n",
    ),
    (
        "> 📖 **本项目已在 GitHub 开源**：<https://github.com/enjoyitxml/LeapControl>，基于开源项目二次开发。\n"
        "> **仅供车友自用体验与交流，请勿外传、请勿二次分发**，也不要上传到应用商店、网盘、论坛等任何公开渠道。\n",
        "> 📖 **本仓库用于个人学习与技术研究**：<https://github.com/enjoyitxml/LeapControl-Personal-use-version>，基于开源项目二次开发。\n"
        "> 与自用版功能完全一致，只去掉品牌标识、补回「支持开发者」与「开源协议与致谢」入口。\n",
    ),
    (
        "| 本仓库 | <https://github.com/enjoyitxml/LeapControl> |\n",
        "| 本仓库 | <https://github.com/enjoyitxml/LeapControl-Personal-use-version> |\n",
    ),
    (
        "完整记录见 [CHANGELOG.md](CHANGELOG.md)，历史安装包在 [Releases](https://github.com/enjoyitxml/LeapControl/releases)。\n",
        "完整记录见 [CHANGELOG.md](CHANGELOG.md)，历史安装包在 [Releases](https://github.com/enjoyitxml/LeapControl-Personal-use-version/releases)。\n",
    ),
    (
        "3. 二次开发部分（界面重构、功能模块自定义、四季主题等）已随本项目一并开源，可供学习与自行编译；**禁止改名换壳后二次分发，禁止任何形式的商业牟利**。\n",
        "3. 二次开发部分（界面重构、功能模块自定义、四季主题等）已随本项目一并开源，可供学习与自行编译。\n",
    ),
    (
        "### 使用范围（请务必遵守）\n"
        "\n"
        "1. 零控本身已在 GitHub 开源：<https://github.com/enjoyitxml/LeapControl>，源码可查阅、可自行编译。\n"
        "2. 二次开发部分同样公开，欢迎提 issue 或 PR 一起完善。\n"
        "3. 本软件**仅供车友自用体验与交流，请勿外传、请勿二次分发**，不要上传到应用商店、网盘、论坛或任何公开渠道。\n"
        "4. 请勿反编译、二次打包、改名换壳后以任何名义重新发布，更不得用于商业牟利。\n",
        "### 使用范围\n"
        "\n"
        "1. 零控本身已在 GitHub 开源：<https://github.com/enjoyitxml/LeapControl-Personal-use-version>，源码可查阅、可自行编译。\n"
        "2. 二次开发部分同样公开，欢迎提 issue 或 PR 一起完善。\n"
        "3. 本版本面向个人学习与技术研究，与自用版功能一致。\n",
    ),
    (
        "### 五、使用范围与传播\n"
        "\n"
        "- 本软件仅供车友自用体验，请勿外传、请勿二次分发，也不要上传到任何应用商店、网盘或论坛。\n"
        "- 本项目源码公开，欢迎查阅与自行编译；但请勿改名换壳后以任何名义重新发布。\n"
        "- 软件按现状提供，不做任何形式的可用性承诺；如后续停止维护，恕不另行通知。\n",
        "### 五、使用范围\n"
        "\n"
        "- 本版本面向个人学习与技术研究，源码公开，欢迎查阅与自行编译。\n"
        "- 软件按现状提供，不做任何形式的可用性承诺；如后续停止维护，恕不另行通知。\n",
    ),
    (
        "2. 本软件基于开源项目二次开发而来，源码已在 GitHub 公开，可自由查阅；但请勿改名换壳后重新分发。\n"
        "3. 本软件**仅供车友自用体验与交流，请勿外传、请勿二次分发**，也不要上传到应用商店、网盘、论坛等任何公开渠道。\n"
        "4. 本软件仅为用户提供便捷控车、车况查看、自定义辅助功能，所有车辆底层权限、车辆协议均来源于用户设备本地合法授权，不破解、不篡改、不绕过官方安全机制。\n"
        "5. 本软件与零跑官方品牌、公司、系统无任何隶属、授权、合作关系，所有功能仅供车主个人学习、自用、体验。\n",
        "2. 本软件基于开源项目二次开发而来，源码已在 GitHub 公开，可自由查阅。\n"
        "3. 本软件仅为用户提供便捷控车、车况查看、自定义辅助功能，所有车辆底层权限、车辆协议均来源于用户设备本地合法授权，不破解、不篡改、不绕过官方安全机制。\n"
        "4. 本软件与零跑官方品牌、公司、系统无任何隶属、授权、合作关系，所有功能仅供车主个人学习、自用、体验。\n",
    ),
    (
        "- 本仓库为**私有**仓库，源码含零跑官方服务端的逆向协议实现，**仅供个人学习与技术研究**，请勿用于商业用途或任何非法目的。\n",
        "- 本仓库为**公开**仓库，源码含零跑官方服务端的逆向协议实现，**仅供个人学习与技术研究**。\n",
    ),
    (
        "- **APK 内的 GitHub 令牌是密文**：仓库转私有后，检测更新与下载安装包都要带一个**只读** PAT。\n"
        "  它以密文写入 `BuildConfig.GITHUB_TOKEN_ENCRYPTED`（构建期由 `app/build.gradle.kts` 做\n"
        "  逐字节 XOR 变长盐编码），运行时由 `ObfuscatedSecrets.getGitHubToken()` 还原，\n"
        "  源码、git 历史与 DEX 常量池里都没有明文（可以拿 `strings` 扫一遍 `classes*.dex` 验证）。\n"
        "  需要轮换时改 `local.properties` 的 `leapcontrol.gh.token` 即可，不必动代码。\n"
        "  **说明白一点**：解混淆算法就在同一个 APK 里，这层只是「不明文」，不是安全存储 ——\n"
        "  所以这颗令牌必须是**最小权限 + 可随时吊销**的，只给该仓库 Releases / Contents 的读权限。\n",
        "- **公开版不带任何内置令牌**：检测更新与下载安装包都匿名访问本仓库的 Releases 接口，\n"
        "  APK 里没有 PAT，构建也不需要 `local.properties` 里的令牌项。\n",
    ),
    (
        "  私有仓库下匿名访问一律 404，网页直链在浏览器里也需要登录态，所以下载只走 API。\n",
        "  公开仓库的 Release 附件可以直接下载，这里的实现仍统一走 API，保持两版代码一致。\n",
    ),
]


def public_readme(src: str) -> str:
    """把自用版 README 改写成公开版的说法（包名、仓库地址、去掉传播限制）。"""
    out = src
    for old, new in README_REWRITES:
        if old not in out:
            raise Fail(
                "README 改写规则没命中，README 可能改过措辞了：\n  "
                + old.splitlines()[0][:80]
            )
        out = out.replace(old, new, 1)
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
    put("app/src/main/AndroidManifest.xml", patched_manifest(
        open(os.path.join(root, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()))
    for rel in WIDGET_LAYOUTS:
        put(rel, patched_widget_layout(open(os.path.join(root, rel), encoding="utf-8").read()))

    put("README.md", public_readme(open(os.path.join(root, "README.md"), encoding="utf-8").read()))

    # README 与自用版共用一份，只按 README_REWRITES 改掉那些只对自用版成立的说法；
    # `docs/screenshots` 必须保留 —— README 里引用了那几张界面预览图，删了图就全裂。
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


def require_binaries(build_dir: str) -> None:
    """构建前确认几个**只以二进制形式存在**的必要文件都在树里。

    2026-11-15 的事故：`app/src/main/assets/shumei.dex` 被 `.gitignore` 的 `*.dex`
    规则吃掉了 —— 本地工作区有、git 里没有，于是自用版（从工作区构建）一切正常，
    公开版（从 `git archive` 出来的树构建）根本装不到安全组件，登录必然报
    「设备安全组件加载失败」，而且构建阶段一点提示都没有。
    这类文件一旦漏掉，只有装到真机上才会发现，所以在构建前先卡一道。
    """
    required = [
        "app/src/main/assets/shumei.dex",
        "app/src/main/jniLibs/arm64-v8a/libsmsdk.so",
        "gradle/wrapper/gradle-wrapper.jar",
        # 公开版的车标：只有公开树里才被引用（自用版用 ic_leap_logo）。
        # 少了它小组件车上左上角就是空白，而且要到真机才看得出来。
        "app/src/main/res/drawable-nodpi/ic_widget_logo_steering.png",
    ]
    missing = [rel for rel in required if not os.path.isfile(os.path.join(build_dir, rel))]
    if missing:
        raise Fail(
            "公开树里缺少必需文件（多半是被 .gitignore 忽略了，本地有但没进 git）：\n  "
            + "\n  ".join(missing)
            + "\n  处理：在 .gitignore 里加 `!<路径>` 反选规则，然后 `git add` 提交。"
        )


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
            require_binaries(build_dir)
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
