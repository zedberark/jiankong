# -*- coding: utf-8 -*-
"""从分散的 drawio 源文件合并生成 docs/NetPulse-图示全量.drawio（论文用）。"""
import re
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent


def strip_mxfile(xml: str) -> str:
    m = re.search(r"<mxfile[^>]*>(.*)</mxfile>", xml, re.DOTALL)
    return m.group(1).strip() if m else ""


def main() -> None:
    system = (BASE / "NetPulse-系统架构图.drawio").read_text(encoding="utf-8")
    func_mod = (BASE / "NetPulse-功能模块图.drawio").read_text(encoding="utf-8")
    classes = (BASE / "NetPulse-类图-分层示意.drawio").read_text(encoding="utf-8")
    er = (BASE / "NetPulse-全局ER图.drawio").read_text(encoding="utf-8")
    flow_full = (BASE / "NetPulse-功能流程图-完整版.drawio").read_text(encoding="utf-8")

    chunks = [
        strip_mxfile(system),
        strip_mxfile(func_mod).replace(
            'name="功能模块图"', 'name="03-功能模块（侧栏菜单）"'
        ),
        strip_mxfile(classes).replace(
            'name="后端分层与设备模块"', 'name="04-后端类图（分层）"'
        ),
        strip_mxfile(er).replace(
            'name="NetPulse-ER图"', 'name="05-数据库全局E-R关系"'
        ),
    ]

    m = re.search(
        r'<diagram id="p0" name="0-系统功能全流程（含登录）">.*?</diagram>',
        flow_full,
        re.DOTALL,
    )
    if m:
        d = m.group(0).replace(
            'name="0-系统功能全流程（含登录）"',
            'name="06-系统功能全流程（登录与入口）"',
        )
        chunks.append(d)

    out = (
        '<mxfile host="app.diagrams.net" modified="2026-04-07T12:00:00.000Z" '
        'agent="NetPulse-thesis" version="22.1.0" type="device">\n'
        + "\n".join(chunks)
        + "\n</mxfile>\n"
    )

    out_path = BASE / "NetPulse-图示全量.drawio"
    out_path.write_text(out, encoding="utf-8")
    print("OK:", out_path, len(out), "bytes")


if __name__ == "__main__":
    main()
