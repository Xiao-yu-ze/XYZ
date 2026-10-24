#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Maa pipeline → .zjs 转换器（针对无尽塔香蕉那套写法）。

## Maa 那套是怎么跑的

- 每个节点 = 「识别 + 动作」，识别结果决定「这个节点现在能不能进」；
- `next` 是**候选列表**：父节点每轮按顺序扫，第一个识别成功的就走进去；
- `timeout: -1` = 一直等到命中；`rate_limit` = 两轮扫描之间的间隔；
- `DirectHit` + `roi` = 一个**固定点**（取 roi 中心），`Swipe` 的
  `begin`/`end` 写字符串时就是引用这种点的**节点名**；
- `anchor` = 具名跳转标记。

## 映射到我们这边

| Maa | 我们 |
|---|---|
| 节点的识别（OCR/ColorMatch） | 条件 `textFount` / `colorFound`（范围、阈值、上下界都带过去） |
| `next: [A, B, C]` | 「等待候选」节点，三个候选各带条件 + `jumpTo` |
| `timeout: -1` | `waitTimeoutMs = 0`（一直等） |
| `rate_limit` | `waitIntervalMs` |
| `Click` + `target: true` | `点击文字` / `点击颜色`（识别到哪就点哪） |
| `Click` + `target: [x,y]` | `点击`（坐标） |
| `Swipe` + `begin`/`end` | `滑动`（起终点解析成坐标） |
| `repeat` / `repeat_delay` | `重复次数` / `重复间隔` |
| `pre_delay` / `post_delay` | 动作前的 `等待` / 动作后插一个 `等待` 节点 |
| `anchor` | 节点的 `jumpId` |

## 用法

    python tools/maa2zjs_towerbanana.py OUT_DIR
"""
import json
import os
import sys

ROOT = r"C:\Users\29374\Desktop\MaaPVZ-v0.5.1-beta.2-win-x86_64\resource\pipeline"
SLOT = os.path.join(ROOT, r"General_action\Plant_planting_Initialize_slot_General_Swipe.json")
GRID = os.path.join(ROOT, r"General_action\Plant_planting_Initialize_Grid_General_Swipe.json")
# 被这三个文件引用、但定义在别处的节点（比如"补给识别"）从这里补进来。
# 只拉被引用到的，不整文件塞 —— 那个文件有几百个节点。
POOLS = [
    os.path.join(ROOT, r"General_action\01_General_actions.json"),
    os.path.join(ROOT, r"Endless\frame\01_frame_Endless.json"),
]
SRC = [
    os.path.join(ROOT, r"Endless\Tower_Banana\TowerBanana_1.json"),
    os.path.join(ROOT, r"Endless\Tower_Banana\TowerBanana_2.json"),
    os.path.join(ROOT, r"Endless\Tower_Banana\TowerBanana_3.json"),
]

BASE = {
    "repeatCount": 1, "delay": "0", "delayUnit": 0, "pauseOnFail": True,
    "screenWidth": 1280, "screenHeight": 720,
}


def load_jsonc(path):
    """读 Maa 的 pipeline：允许 // 注释和尾逗号（标准 json 不认）。"""
    import re
    with open(path, encoding="utf-8") as f:
        txt = f.read()
    out = []
    in_str = False
    esc = False
    i = 0
    while i < len(txt):
        ch = txt[i]
        if in_str:
            out.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < len(txt) and txt[i + 1] == "/":
            while i < len(txt) and txt[i] != "\n":
                i += 1
            continue
        out.append(ch)
        i += 1
    txt = "".join(out)
    # 去尾逗号
    txt = re.sub(r",(\s*[}\]])", r"\1", txt)
    return json.loads(txt)


def load_points():
    """把 DirectHit 节点读成 名字 → (x, y)。"""
    pts = {}
    for path in (SLOT, GRID):
        data = load_jsonc(path)
        for name, node in data.items():
            rec = node.get("recognition")
            if isinstance(rec, dict) and rec.get("type") == "DirectHit":
                roi = rec["param"]["roi"]
                pts[name] = (int(roi[0] + roi[2] // 2), int(roi[1] + roi[3] // 2))
    return pts


def area(roi):
    """Maa 的 [x,y,w,h] → 我们的 '左 上 右 下'。"""
    if not roi:
        return None
    x, y, w, h = roi
    return "%d %d %d %d" % (x, y, x + w, y + h)


def point_of(value, pts):
    """begin/end 可能是 [x,y]、[x,y,w,h] 或节点名。"""
    if isinstance(value, list):
        if len(value) >= 4:
            return (int(value[0] + value[2] // 2), int(value[1] + value[3] // 2))
        return (int(value[0]), int(value[1]))
    if isinstance(value, str):
        if value not in pts:
            raise SystemExit("点未定义：%s（不在槽位/格子表里）" % value)
        return pts[value]
    raise SystemExit("无法解析的点：%r" % (value,))


def condition_of(node):
    """节点的识别 → 我们的条件。没有识别返回 None（= 无条件）。"""
    rec = node.get("recognition")
    if rec is None:
        return None
    if isinstance(rec, str):
        rtype, param = rec, {}
    else:
        rtype, param = rec.get("type"), rec.get("param") or {}

    if rtype == "OCR":
        expected = node.get("expected") or param.get("expected")
        if not expected:
            return None
        thr = node.get("threshold", param.get("threshold"))
        pos = {
            "type": "text",
            "text": expected,
            # Maa 的 threshold 是文字相似度，默认 0.3；我们缺省 90(%)，按 0~100 传
            "similarPercent": int(round(float(thr) * 100)) if thr is not None else 90,
        }
        a = area(node.get("roi") or param.get("roi"))
        if a:
            pos["limitArea"] = a
        # ★ 条件的"位置"不是 posData，而是**按类型分的键**：
        #   image / text / colorData / node（见 ZModel.Condition.parse）。
        #   写成 posData 会被静默忽略 —— 条件就变成"没有范围没有文字"。
        return {"type": "textFount", "text": pos}

    if rtype == "ColorMatch":
        lower = node.get("lower") or param.get("lower")
        upper = node.get("upper") or param.get("upper")
        if lower is None or upper is None:
            return None
        pos = {
            "type": "color",
            "colorLower": list(lower),
            "colorUpper": list(upper),
            # 给个占位色，免得某些分支只认 color
            "color": (int(lower[0]) << 16) | (int(lower[1]) << 8) | int(lower[2]),
        }
        a = area(node.get("roi") or param.get("roi"))
        if a:
            pos["limitArea"] = a
        return {"type": "colorFound", "colorData": pos}

    # DirectHit / TemplateMatch / 其它：这套脚本里没用到，有就明说
    return None


def grep_action(node):
    act = node.get("action")
    if act is None:
        return ("none", {})
    if isinstance(act, str):
        return (act, node)
    return (act.get("type"), act.get("param") or {})


def common(node):
    """通用字段：延迟、重复、jumpId。"""
    c = {}
    if node.get("pre_delay"):
        c["delay"] = str(int(node["pre_delay"]))
    if node.get("repeat"):
        c["repeatCount"] = int(node["repeat"])
    if node.get("repeat_delay") is not None:
        c["repeatDelayValue"] = str(int(node["repeat_delay"]))
    return c


def zjs_action(atype, act_param, node, pts, jump_id):
    """一个 Maa 节点 → 我们的动作 dict（不含 next 路由）。"""
    c = common(node)
    cond = condition_of(node)

    if atype in ("DoNothing", "none"):
        # 什么都不做：用 0 延迟的等待充当"路由节点"
        d = {"type": "控制执行", "controlRunType": "waitDelay", "delay": c.get("delay", "0")}
        d["delayUnit"] = 0
        if cond:
            d["condition"] = cond
        d.update({"jumpId": jump_id})
        d["desc"] = "路由（Maa: DoNothing）"
        return d

    if atype == "Click":
        target = act_param.get("target", node.get("target"))
        use_rec = target is True or (target is None and cond is not None)
        if use_rec and cond is not None:
            # 识别到哪点哪 —— 直接用 点击文字/点击颜色（一步完成找+点）
            t = "点击文字" if cond["type"] == "textFount" else "点击颜色"
            d = {"type": t, "posData": cond.get("text") or cond.get("colorData")}
        else:
            pt = point_of(target, pts) if target is not None else (640, 360)
            d = {"type": "点击", "posData": {"type": "location", "x": str(pt[0]), "y": str(pt[1])}}
            if cond:
                d["condition"] = cond
        d.update(c)
        d["jumpId"] = jump_id
        return d

    if atype == "Swipe":
        b = point_of(node["begin"], pts)
        e = point_of(node["end"], pts)
        d = {
            "type": "滑动",
            "startPos": {"type": "location", "x": str(b[0]), "y": str(b[1])},
            "endPos": {"type": "location", "x": str(e[0]), "y": str(e[1])},
            "duration": str(int(node.get("duration") or 300)),
        }
        if cond:
            d["condition"] = cond
        d.update(c)
        d["jumpId"] = jump_id
        return d

    # 这套脚本里没出现过的动作类型：生成一个"明确跳过"的提示节点
    d = {"type": "控制执行", "controlRunType": "waitDelay", "delay": "0", "delayUnit": 0}
    d["jumpId"] = jump_id
    d["desc"] = "未转换的 Maa 动作：%s" % atype
    return d


def build(files, pts):
    """把所有节点摊成一条顺序队列 + 路由节点。"""
    out = []
    by_name = {}
    for path in files:
        data = load_jsonc(path)
        for name, node in data.items():
            if name in by_name:
                raise SystemExit("节点重名：%s（%s）" % (name, path))
            by_name[name] = node

    # ---- 按引用补外部节点（有上限，避免把整个通用库搬进来）
    pool = {}
    for path in POOLS:
        if not os.path.exists(path):
            continue
        try:
            pool.update(load_jsonc(path))
        except Exception as e:
            print("  跳过外部文件（解析失败）：%s — %s" % (os.path.basename(path), e))
    imported = set()
    changed = True
    while changed and len(imported) < 80:
        changed = False
        refs = set()
        for node in by_name.values():
            for key in ("next",):
                v = node.get(key)
                if isinstance(v, str):
                    v = [v]
                for x in (v or []):
                    if isinstance(x, str) and not x.startswith("[Anchor]"):
                        refs.add(x)
            for key in ("begin", "end"):
                pass
        for name in refs:
            if name in by_name:
                continue
            if name in pool:
                by_name[name] = pool[name]
                imported.add(name)
                changed = True
    if imported:
        print("  外部引用补入 %d 个节点：%s" % (len(imported), "、".join(sorted(imported)[:6])))

    # 每个节点：动作节点（jumpId=名字）+ 路由节点（按 next 派发）
    def resolve_ref(ref):
        """节点名，或 `[Anchor]名字`（→ 声明该 anchor 的节点）。解析不出返回 None。"""
        if not isinstance(ref, str):
            return None
        if not ref.startswith("[Anchor]"):
            return ref
        aname = ref[len("[Anchor]"):]
        for other in by_name.values():
            a = other.get("anchor") or {}
            if isinstance(a, dict) and aname in a:
                return a[aname]
        return None

    # Maa 用 `next: [种植物_初始化_植物位置]` 把点位表串进来（只是为了让
    # begin/end 的名字能解析）。真正入口是 anchor 里那个名字 —— 直接取它，
    # 否则脚本会先去把 61 个点走一遍。
    entry_skip = set()
    for node in by_name.values():
        anchors = node.get("anchor") or {}
        if isinstance(anchors, dict) and "初始化" in anchors:
            for nxt in (node.get("next") or []):
                entry_skip.add(nxt)

    for name, node in by_name.items():
        atype, act_param = grep_action(node)
        out.append(zjs_action(atype, act_param, node, pts, name))

        # post_delay：Maa 是"动作后等多久"，我们插一个等待节点
        post = int(node.get("post_delay") or 0)
        if post > 0:
            out.append({
                "type": "控制执行", "controlRunType": "waitDelay",
                "delay": str(post), "delayUnit": 0,
                "desc": "%s 收尾等待 %dms" % (name, post),
            })

        nxt = node.get("next")
        if not nxt:
            # 没有 next：要么是终点，要么靠 [Anchor] 回跳
            continue
        if isinstance(nxt, list) and all(x in entry_skip for x in nxt):
            # 入口的"走点位表"那条：改成按 anchor 进真正的第一步
            anchors = node.get("anchor") or {}
            target = anchors.get("初始化")
            if target:
                out.append({
                    "type": "控制执行", "controlRunType": "jumpTo",
                    "jumpToPosition": ":" + target,
                    "desc": "入口 → %s（Maa anchor 初始化）" % target,
                })
            continue
        if isinstance(nxt, str):
            nxt = [nxt]

        # 单个后继：直接跳
        if len(nxt) == 1:
            tgt = resolve_ref(nxt[0])
            if tgt:
                out.append({
                    "type": "控制执行", "controlRunType": "jumpTo",
                    "jumpToPosition": ":" + tgt,
                    "desc": "%s → %s" % (name, tgt),
                })
            else:
                out.append({
                    "type": "控制执行", "controlRunType": "waitDelay",
                    "delay": "0", "delayUnit": 0,
                    "desc": "⚠ 未解析的引用 %s（%s 之后原样停在这里）" % (nxt[0], name),
                })
            continue

        # 多后继：等待候选，按顺序试
        cands = []
        for child in nxt:
            if isinstance(child, str) and child.startswith("[Anchor]"):
                target = resolve_ref(child)
                cands.append({"jumpTo": target} if target else {"jumpTo": None})
                continue
            cnode = by_name.get(child)
            ccond = condition_of(cnode) if cnode else None
            cands.append({
                "condition": ccond,
                "jumpTo": child,
            })
        timeout = node.get("timeout")
        interval = int(node.get("rate_limit") or 100)
        out.append({
            "type": "控制执行", "controlRunType": "waitDelay",   # 占位类型，下面覆盖
            "delay": "0", "delayUnit": 0,
            "desc": "%s 的 next（%d 个候选）" % (name, len(nxt)),
        })
        waiter = out.pop()
        waiter["type"] = "等待候选"
        waiter["candidates"] = cands
        # timeout -1 = 一直等（我们 0 表示一直等）；其它值按毫秒
        waiter["waitTimeoutMs"] = 0 if (timeout == -1 or timeout is None) else int(timeout)
        waiter["waitIntervalMs"] = interval
        out.append(waiter)

    return out


def write(path, nodes, desc):
    lines = []
    base = dict(BASE)
    base["description"] = desc
    lines.append(json.dumps(base, ensure_ascii=False, separators=(",", ":")))
    for n in nodes:
        lines.append(json.dumps(n, ensure_ascii=False, separators=(",", ":")))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\r\n".join(lines) + "\r\n")
    print("  %-34s %d 个节点" % (os.path.basename(path), len(nodes)))


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else "build/zjs"
    os.makedirs(outdir, exist_ok=True)
    pts = load_points()
    print("点位表：%d 个固定点" % len(pts))

    # 三个文件合成一条完整脚本（它们本身就是同一条链）
    nodes = build(SRC, pts)
    write(os.path.join(outdir, "塔香蕉_无尽.zjs"), nodes,
          "无尽塔香蕉（由 Maa pipeline TowerBanana_1/2/3 转换）")

    # 也各自单独出一份，方便对着原文件看
    for i, src in enumerate(SRC, 1):
        nodes_i = build([src], pts)
        write(os.path.join(outdir, "塔香蕉_第%d段.zjs" % i), nodes_i,
              "无尽塔香蕉 第 %d 段（TowerBanana_%d.json）" % (i, i))


if __name__ == "__main__":
    main()
