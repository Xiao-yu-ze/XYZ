#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Maa pipeline → .zjs（条件内联风格，和手写脚本一样紧凑）。

## 为什么要重写这一版

第一版把 Maa 的每个节点拆成「动作节点 + 收尾等待 + 跳转/等待候选 + 停止」，
181 个节点、149 条跳转。用户指出：本来一个节点能做完的事被拆成了四个，
而且「控制执行·等待」这个节点和动作自带的等待字段完全重复。

对手写脚本的分析（常规四脚僵王无尽 心塔 1.3.zjs，106 个动作）显示真实做法是：

    {"type":"点击","posData":{"type":"location","x":"86.7%","y":"94%"},
     "condition":{"type":"colorFound","repeatWhenFalse":true,
                  "repeatWhenFalseLimitTimes":200,"repeatWhenFalseRepeatDelay":100,
                  "colorData":{"type":"color","color":"#7b45d6",
                               "limitPosX":"84.7%","limitPosY":"91.7%","similarPercent":100}}}

即「**动作自己带条件 + 反复检查**」：等到那个颜色出现 → 点这里，最多等 200×100ms。
一个节点干完，不需要路由节点，也不需要跳转。

所以这一版：

- 线性节点（后继只有一个）：**只出动作**，识别变成条件 + repeatWhenFalse，
  等待时长用 timeout / rate_limit 换算；
- 「点击自己识别到的位置」：识别进 posData，用动作自己的 waitTimeoutMs 反复找
  （引擎的 locate 本来就带重试），仍是**一个节点**；
- 真正分叉（next 有多个候选）：才用「等待候选」，且超时用 onTimeoutStop / onTimeoutJump
  表达，不再塞一个「停止」节点；
- post_delay 累加进**下一个节点**的 delay（delay 在识别之前，与 Maa 的
  「动作后 → 下一个节点识别前」等价），不再生成等待节点；
- 纯 DoNothing 且只有一个后继（Maa 用来串流程的空节点）**直接丢掉**。

## 用法

    python tools/maa2zjs.py OUT_DIR [--repeat-gap-ms N]
"""
import json
import os
import sys

ROOT = r"C:\Users\29374\Desktop\MaaPVZ-v0.5.1-beta.2-win-x86_64\resource\pipeline"
SLOT = os.path.join(ROOT, r"General_action\Plant_planting_Initialize_slot_General_Swipe.json")
GRID = os.path.join(ROOT, r"General_action\Plant_planting_Initialize_Grid_General_Swipe.json")
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

# ★ 动作上的 delay 必须**显式**带 delayUnit。
#   我们模型的缺省是 1 = 秒（ZModel.TimeUnit），不写就等于 "200 秒"。
#   用户手写脚本里每个动作都带着这个字段，就是因为它不能省。
MS = 0

# maa 5.14.2 pipeline.py 的 JPipelineData 默认值（不是猜的）
DEFAULT_RATE_LIMIT = 1000
DEFAULT_TIMEOUT = 20000
DEFAULT_DELAY = 200

# 连点间隔：Maa 的 repeat 是"识别一次、动作重复 N 次"，我们对应 fastRepeatMode。
# 一次注入 5~15ms；想贴近 Maa 的实际节拍就传 --repeat-gap-ms 30。
REPEAT_GAP_MS = 0


def load_jsonc(path):
    """读 Maa 的 pipeline：允许 // 注释和尾逗号（标准 json 不认）。"""
    import re
    with open(path, encoding="utf-8") as f:
        txt = f.read()
    out, in_str, esc = [], False, False
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
    return json.loads(re.sub(r",(\s*[}\]])", r"\1", "".join(out)))


def load_points():
    """DirectHit 节点 = 固定点，取 roi 中心。"""
    pts = {}
    for path in (SLOT, GRID):
        for name, node in load_jsonc(path).items():
            rec = node.get("recognition")
            if isinstance(rec, dict) and rec.get("type") == "DirectHit":
                roi = rec["param"]["roi"]
                pts[name] = (int(roi[0] + roi[2] // 2), int(roi[1] + roi[3] // 2))
    return pts


def to_pct(v, total):
    """Maa 是绝对像素；手写脚本用的是百分比。我们用绝对像素更不容易错，
    但为了让编辑器显示一致，这里统一写像素字符串。"""
    return str(int(v))


def int_field(node, key, default):
    v = node.get(key)
    return default if v is None else int(v)


def next_names(raw):
    """next 的元素是 JNodeAttr：字符串，或 {name, jump_back, anchor}。"""
    if raw is None:
        return []
    if not isinstance(raw, list):
        raw = [raw]
    out = []
    for item in raw:
        if isinstance(item, str):
            out.append(item)
        elif isinstance(item, dict):
            nm = item.get("name")
            if item.get("anchor"):
                out.append("[Anchor]" + str(nm))
            else:
                out.append(nm)
    return [x for x in out if x]


def hex_of(rgb):
    return "#%02x%02x%02x" % (int(rgb[0]), int(rgb[1]), int(rgb[2]))


def recognition_of(node):
    """Maa 识别 → (posData, 其它)。返回 None 表示无识别。"""
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
            "similarPercent": int(round(float(thr) * 100)) if thr is not None else 90,
        }
        roi = node.get("roi") or param.get("roi")
        if roi:
            x, y, w, h = roi
            pos["limitArea"] = "%d %d %d %d" % (x, y, x + w, y + h)
        return pos

    if rtype == "ColorMatch":
        lower = node.get("lower") or param.get("lower")
        upper = node.get("upper") or param.get("upper")
        if lower is None or upper is None:
            return None
        roi = node.get("roi") or param.get("roi")
        mid = [(float(lower[i]) + float(upper[i])) / 2 for i in range(3)]

        # ★ 这里有三件事必须和 Maa 一致，写错过一次：

        # 1. Maa 的颜色判定是「每个通道落在 [lower, upper] 里」的**范围**判定，
        # 不是 100% 精确匹配。写成 similarPercent=100 等于要求像素完全等于某色 ——
        # 在游戏画面里基本必然识别不到。
        # 2. 所以相似度按范围的**半宽**折算：半宽 10/255 ≈ 4% → 96。
        # 这样即使有人把上下界删掉，容差仍接近原来的范围。
        # 3. 不能只判 roi 中心一个像素（limitPosX/Y）：Maa 是在整个 roi 里找，
        # 只判中心会把"颜色稍有偏移/被遮挡"的情况全判错。

        half = max(float(upper[i]) - float(lower[i]) for i in range(3)) / 2.0
        pos = {
            "type": "color",
            "color": hex_of(mid),          # 仅供显示（范围中点）
            "colorLower": list(lower),      # 真正的判定依据
            "colorUpper": list(upper),
            "similarPercent": max(1, int(round(100 - half / 255.0 * 100))),
        }
        if roi:
            x, y, w, h = roi
            pos["limitArea"] = "%d %d %d %d" % (x, y, x + w, y + h)
        return pos

    return None


class Conv:
    def __init__(self, files, pts):
        self.pts = pts
        self.by_name = {}
        self.origin = {}
        self.task_files = set(files)
        pool, pool_src = {}, {}
        for path in POOLS:
            if not os.path.exists(path):
                continue
            try:
                data = load_jsonc(path)
            except Exception as e:
                print("  跳过外部文件：%s — %s" % (os.path.basename(path), e))
                continue
            for k in data:
                pool_src[k] = path
            pool.update(data)
        for path in files:
            for name, node in load_jsonc(path).items():
                self.by_name[name] = node
                self.origin[name] = path
        # 按引用补外部节点
        imported = set()
        changed = True
        while changed and len(imported) < 80:
            changed = False
            for node in list(self.by_name.values()):
                for ref in next_names(node.get("next")):
                    nm = ref[len("[Anchor]"):] if ref.startswith("[Anchor]") else ref
                    if nm in self.by_name or nm not in pool:
                        continue
                    self.by_name[nm] = pool[nm]
                    self.origin[nm] = pool_src.get(nm, "")
                    imported.add(nm)
                    changed = True
        if imported:
            print("  外部引用补入 %d 个节点" % len(imported))

    def resolve(self, ref, from_node=None):
        """节点名 或 [Anchor]名字 → 真实节点名（按作用域挑，不猜）。"""
        if not isinstance(ref, str):
            return None
        if not ref.startswith("[Anchor]"):
            return ref
        aname = ref[len("[Anchor]"):]
        cands = [(n, a["anchor"][aname]) for n, a in self.by_name.items()
                 if isinstance(a.get("anchor"), dict) and aname in a["anchor"]]
        if not cands:
            return None
        in_task = [c for c in cands if self.origin.get(c[0]) in self.task_files]
        if len(in_task) == 1:
            return in_task[0][1]
        if from_node and from_node in self.origin:
            same = [c for c in cands if self.origin.get(c[0]) == self.origin.get(from_node)]
            if len(same) == 1:
                return same[0][1]
        if len(cands) == 1:
            return cands[0][1]
        print("  ⚠ anchor「%s」有 %d 处声明，无法确定作用域" % (aname, len(cands)))
        return cands[0][1]

    def successors(self, name):
        node = self.by_name.get(name) or {}
        return [x for x in (self.resolve(r, from_node=name)
                            for r in next_names(node.get("next"))) if x]

    def action_of(self, name, node, pts):
        """一个 Maa 节点 → 我们的动作 dict（识别内联成条件或 posData）。"""
        act = node.get("action")
        if isinstance(act, str):
            atype, aparam = act, node
        elif isinstance(act, dict):
            atype, aparam = act.get("type"), (act.get("param") or {})
        else:
            atype, aparam = None, node

        pos = recognition_of(node)
        timeout = int_field(node, "timeout", DEFAULT_TIMEOUT)
        if node.get("timeout") is not None and int(node["timeout"]) == -1:
            timeout = -1
        interval = max(1, int_field(node, "rate_limit", DEFAULT_RATE_LIMIT))

        # 条件：识别 → repeatWhenFalse（等到出现为止）
        cond = None
        if pos is not None:
            times = 0 if timeout < 0 else max(1, timeout // interval)
            cond = {
                "type": "colorFound" if pos["type"] == "color" else "textFount",
                "repeatWhenFalse": True,
                "repeatWhenFalseLimitTimes": times,
                "repeatWhenFalseRepeatDelay": interval,
                ("colorData" if pos["type"] == "color" else "text"): pos,
            }

        d = {}
        if atype in (None, "DoNothing"):
            if cond is None:
                return None      # 纯空节点：丢掉
            # 有识别但没动作：只等条件成立，不做事
            d = {"type": "控制执行", "controlRunType": "waitDelay",
                 "delay": "0", "delayUnit": 0, "condition": cond}
        elif atype == "Click":
            target = aparam.get("target", node.get("target"))
            self_click = target is True or (target is None and pos is not None)
            if self_click and pos is not None:
                # 点自己识别到的位置 —— 一个节点搞定：
                # posData 负责找（locate 自带重试），不需要再挂条件
                d = {"type": "点击颜色" if pos["type"] == "color" else "点击文字",
                     "posData": pos,
                     "waitTimeoutMs": timeout,
                     "waitIntervalMs": interval}
                cond = None
            else:
                pt = self.pts_of(target) if target is not None else (640, 360)
                d = {"type": "点击",
                     "posData": {"type": "location", "x": str(pt[0]), "y": str(pt[1])},
                     # Maa 的 Click 是"点一下"，没有按住时长；缺省给 10ms。
                     # 写 100（界面默认）会让 35 连点变成 3.5 秒，
                     # 而手写脚本里的点波就是 duration 5~20ms。
                     "duration": str(int(node.get("duration") or 10))}
                if cond:
                    d["condition"] = cond
        elif atype == "Swipe":
            b = self.pts_of(node.get("begin"))
            e = self.pts_of(node.get("end"))
            d = {"type": "滑动",
                 "startPos": {"type": "location", "x": str(b[0]), "y": str(b[1])},
                 "endPos": {"type": "location", "x": str(e[0]), "y": str(e[1])},
                 "duration": str(int(node.get("duration") or 300))}
            if cond:
                d["condition"] = cond
        else:
            return {"type": "控制执行", "controlRunType": "waitDelay",
                    "delay": "0", "delayUnit": 0,
                    "desc": "未转换的 Maa 动作：%s" % atype}

        # 重复：Maa 是"识别一次、动作重复 N 次" → fastRepeatMode
        rc = node.get("repeat")
        if rc and int(rc) > 1:
            d["repeatCount"] = int(rc)
            d["fastRepeatMode"] = True

            # ★ 重复间隔必须**显式写**，不能留给默认值。

            # 引擎的重复间隔缺省会回落到动作 delay，而 delay 缺省是 200ms ——
            # 于是 Maa 里「repeat:250, pre_delay:0, post_delay:0, duration:1」
            # 的划飞弹（250 次快速滑动）在我们这儿变成 250 × 208ms ≈ 52 秒，
            # 真机日志里 52 号节点就是这么来的。

            # 按 Maa 的原意：轮间间隔取 pre_delay/post_delay 的较大者（这里都是 0），
            # --repeat-gap-ms 只作为下限（注入太快会被游戏合并时才需要）。

            #
            # 只用工装下限，**不要**把 pre_delay/post_delay 当轮间隔：
            #   它们是"整个节点执行之前/之后各等一次"，引擎已经把 pre_delay
            #   放在重复循环之外了。若把它们塞进轮间隔，点波2（pre_delay 2000、
            #   repeat 60）会变成 60×2s = 120 秒，而 Maa 的原意是"等 2 秒，然后连点 60 下"。
            gap_src = REPEAT_GAP_MS
            d["repeatDelayValue"] = str(gap_src)
            d["repeatDelayUnit"] = MS

        # pre_delay：识别之后、动作之前
        pre = int_field(node, "pre_delay", DEFAULT_DELAY)
        d["delayUnit"] = MS
        if pre > 0:
            if "posData" in d and d["type"] in ("点击文字", "点击颜色", "点击图片"):
                d["delayAfterLocate"] = str(pre)
            else:
                d["delay"] = str(pre)

        d["jumpId"] = name
        return d

    def pts_of(self, v):
        if isinstance(v, list):
            if len(v) >= 4:
                return (int(v[0] + v[2] // 2), int(v[1] + v[3] // 2))
            return (int(v[0]), int(v[1]))
        if isinstance(v, str) and v in self.pts:
            return self.pts[v]
        raise SystemExit("点未解析：%r" % (v,))

    def chain_end(self, name, acts, succ, depth=0):
        """
        穿透"空节点"（无动作、单后继）直到落到有动作或分叉的节点。

        为什么需要：`tower_banana_前期` 是 DoNothing + 单后继，被丢掉后，
        过渡1 的三路分叉里指向它的候选就成了悬空跳转。它存在的意义就是
        "无条件继续往下"，所以直接把候选指向它的下一站即可。
        """
        seen = set()
        cur = name
        while depth < 50:
            depth += 1
            if cur in acts and acts[cur] is not None:
                return cur
            nxt = succ.get(cur) or []
            if len(nxt) != 1:
                return cur          # 分叉或终点：保留原样
            cur = nxt[0]
            if cur in seen:
                return cur
            seen.add(cur)
        return cur

    def build(self, entry_hint=None):
        out = []
        # 1) 每个节点产出"自己的动作"（丢掉纯空节点）
        acts = {}
        for name, node in self.by_name.items():
            a = self.action_of(name, node, self.pts)
            if a is not None:
                acts[name] = a

        # 2) 分叉节点与后继统计
        succ = {n: self.successors(n) for n in self.by_name}
        # ★ "丢掉纯空节点"这条只对**非分叉**成立。
        #   `通用_神器补给_判断是否有神器` 就是 DoNothing + 6 个 next —— 它没有动作，
        #   但那次分叉是真实存在的流程。第一版把它一起丢了，整个补给选择分支消失，
        #   而且不报错（脚本照跑，只是再也不会去选补给）。
        branch_only = [n for n in self.by_name
                       if n not in acts and len(succ.get(n) or []) >= 2]
        for n in branch_only:
            acts[n] = None
        order = [n for n in self.by_name if n in acts]
        if entry_hint and entry_hint in order:
            order.remove(entry_hint)
            order.insert(0, entry_hint)

        emitted_names = []
        for name in order:
            node = self.by_name[name]
            a = acts[name]
            if a is not None:
                out.append(a)
                emitted_names.append(name)

            nxt = succ.get(name) or []
            if len(nxt) >= 2:
                # 真分叉：等待候选（这才是需要它的地方）
                cands = []
                for child in nxt:
                    child = self.chain_end(child, acts, succ)
                    cnode = self.by_name.get(child) or {}
                    cpos = recognition_of(cnode)
                    if cpos is None:
                        cands.append({"jumpTo": child})
                    else:
                        t = 0 if int_field(cnode, "timeout", DEFAULT_TIMEOUT) < 0 else \
                            max(1, int_field(cnode, "timeout", DEFAULT_TIMEOUT) //
                                max(1, int_field(cnode, "rate_limit", DEFAULT_RATE_LIMIT)))
                        cands.append({
                            "condition": {
                                "type": "colorFound" if cpos["type"] == "color" else "textFount",
                                "repeatWhenFalse": False,
                                ("colorData" if cpos["type"] == "color" else "text"): cpos,
                            },
                            "jumpTo": child,
                        })
                w = {
                    "type": "等待候选",
                    "candidates": cands,
                    "waitTimeoutMs": (0 if int_field(node, "timeout", DEFAULT_TIMEOUT) < 0
                                      else int_field(node, "timeout", DEFAULT_TIMEOUT)),
                    "waitIntervalMs": max(1, int_field(node, "rate_limit", DEFAULT_RATE_LIMIT)),
                    "desc": "%s 分叉：%d 个候选" % (name, len(nxt)),
                }
                err = next_names(node.get("on_error"))
                if err:
                    tgt = self.resolve(err[0], from_node=name)
                    if tgt:
                        w["onTimeoutJump"] = self.chain_end(tgt, acts, succ)
                else:
                    w["onTimeoutStop"] = True
                out.append(w)

        # 4) post_delay：加进"下一个"节点的 delay（同一顺序队列里）
        for i, a in enumerate(out[:-1]):
            name = a.get("jumpId")
            if not name:
                continue
            post = int_field(self.by_name.get(name, {}), "post_delay", DEFAULT_DELAY)
            if post <= 0:
                continue
            nxt = out[i + 1]
            cur = int(float(nxt.get("delay") or 0))
            nxt["delay"] = str(cur + post)
            nxt["delayUnit"] = MS

        return out


def write(path, nodes, desc, base_extra=None):
    base = dict(BASE)
    base.update(base_extra or {})
    base["description"] = desc
    lines = [json.dumps(base, ensure_ascii=False, separators=(",", ":"))]
    for n in nodes:
        lines.append(json.dumps(n, ensure_ascii=False, separators=(",", ":")))
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("\r\n".join(lines) + "\r\n")
    print("  %-30s %d 个节点" % (os.path.basename(path), len(nodes)))


def main():
    args = list(sys.argv[1:])
    global REPEAT_GAP_MS
    if "--repeat-gap-ms" in args:
        i = args.index("--repeat-gap-ms")
        REPEAT_GAP_MS = int(args[i + 1])
        del args[i:i + 2]
    outdir = args[0] if args else "build/zjs"
    os.makedirs(outdir, exist_ok=True)

    pts = load_points()
    print("点位表：%d 个固定点" % len(pts))
    conv = Conv(SRC, pts)
    nodes = conv.build(entry_hint="Tower_Banana_Entry")
    write(os.path.join(outdir, "塔香蕉_无尽.zjs"), nodes,
          "无尽塔香蕉（Maa pipeline 转换，条件内联）")

    for i, src in enumerate(SRC, 1):
        c = Conv([src], pts)
        write(os.path.join(outdir, "塔香蕉_第%d段.zjs" % i), c.build(),
              "无尽塔香蕉 第 %d 段" % i)


if __name__ == "__main__":
    main()
