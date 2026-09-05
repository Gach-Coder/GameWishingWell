# -*- coding: utf-8 -*-
"""
旧游戏迁移：为在新版本"保存点/撤销"机制之前保存的游戏补建 .savepoint，
使撤销功能恢复精确语义（保存区文件 + 保存点上下文回滚）。

用法（游戏 id 见 files/games/ 目录名；cut 为保存点保留的消息条数，缺省=全部）：
    python scripts/migrate-savepoint.py --package com.gamewishingwell \
        --games "1788586581865:7,1788535900878"

cut 的确定方法：保存区的 index.html 是哪一轮对话的产物，就在该轮 assistant
回复之后切割（其后的消息属于未保存的编辑轮，不进保存点）。
cut 之后 state 中的 rollingSummary/knownIssues/lastError 会一并清空
（它们描述的是未保存版本）；cut=全部 时保留原值（保存区即最新轮产物，摘要一致）。

仅写 games/<id>/.savepoint/，不动保存区游戏文件、实时会话副本与编辑区工作区。
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

IDLE_STAGE = "空闲"


def adb(adb_path, *args, stdin_file=None):
    cmd = [adb_path] + list(args)
    if stdin_file:
        with open(stdin_file, "rb") as f:
            return subprocess.run(cmd, stdin=f, capture_output=True, check=True).stdout
    return subprocess.run(cmd, capture_output=True, check=True).stdout


def run_as_read(adb_path, package, path):
    out = adb(adb_path, "shell", f"run-as {package} cat {path}")
    # adb shell 会把 LF 转成 CRLF，统一还原（均为文本文件）
    return out.replace(b"\r\n", b"\n").decode("utf-8")


def run_as_write(adb_path, package, path, local_file):
    adb(adb_path, "shell", f"run-as {package} sh -c 'mkdir -p $(dirname {path}) && cat > {path}'", stdin_file=local_file)


def migrate(adb_path, package, game_id, cut):
    base = f"files/games/{game_id}"
    messages = json.loads(run_as_read(adb_path, package, f"{base}/session.json"))
    state = json.loads(run_as_read(adb_path, package, f"{base}/agent_state.json"))
    saved_html = run_as_read(adb_path, package, f"{base}/index.html")

    if cut is None or cut >= len(messages):
        kept = messages
        truncated = False
    else:
        kept = messages[:cut]
        truncated = True
    print(f"game {game_id}: 保留 {len(kept)}/{len(messages)} 条消息作为保存点"
          f"{'（清空描述未保存版本的摘要）' if truncated else '（全量，摘要保留）'}")

    sp_state = dict(state)
    sp_state["messages"] = kept
    sp_state["currentHtml"] = saved_html
    sp_state["agentStage"] = IDLE_STAGE
    sp_state["isGenerating"] = False
    sp_state["error"] = None
    sp_state["streamingText"] = None
    sp_state["lastWarning"] = None
    sp_state["pendingConfirmation"] = None
    if truncated:
        sp_state["rollingSummary"] = ""
        sp_state["knownIssues"] = []
        sp_state["lastError"] = None
        sp_state["lastErrorSignature"] = None

    with tempfile.TemporaryDirectory() as tmp:
        sp_session = os.path.join(tmp, "session.json")
        sp_agent = os.path.join(tmp, "agent_state.json")
        with open(sp_session, "w", encoding="utf-8", newline="\n") as f:
            json.dump(kept, f, ensure_ascii=False)
        with open(sp_agent, "w", encoding="utf-8", newline="\n") as f:
            json.dump(sp_state, f, ensure_ascii=False)
        run_as_write(adb_path, package, f"{base}/.savepoint/session.json", sp_session)
        run_as_write(adb_path, package, f"{base}/.savepoint/agent_state.json", sp_agent)

    # 回读校验
    back = json.loads(run_as_read(adb_path, package, f"{base}/.savepoint/agent_state.json"))
    assert len(back.get("messages", [])) == len(kept), "保存点消息数校验失败"
    assert back.get("currentHtml") == saved_html, "保存点 currentHtml 校验失败"
    print(f"game {game_id}: .savepoint 已写入并校验通过")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--package", default="com.gamewishingwell")
    ap.add_argument("--games", required=True,
                    help='形如 "id:cut,id"（cut 缺省=全部消息），多个游戏逗号分隔')
    args = ap.parse_args()

    for item in args.games.split(","):
        item = item.strip()
        if not item:
            continue
        game_id, _, cut = item.partition(":")
        migrate(args.adb, args.package, game_id.strip(), int(cut) if cut.strip() else None)
    print("迁移完成：撤销（保存区覆盖编辑区 + 上下文回滚到保存点）已可用。")


if __name__ == "__main__":
    sys.exit(main())
