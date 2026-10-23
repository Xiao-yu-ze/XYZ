#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 MaaFramework pipeline 翻译成自动精灵 .zjs。

## 为什么要有这个工具

两边的执行模型不一样，一对一翻译做不到：

```
Maa        一棵节点图。每次 tick 从当前节点出发，按 next 顺序逐个试，
           第一个"识别成功"的节点被进入，执行它的 action，再继续。

自动精灵    一条线性动作列表。条件不满足就跳过这一条，往下走；
           需要跳转就显式写「控制执行 → 跳转」。
```

所以这里的做法是：**把图展开成线性列表，用「条件 + 跳转」把分支补回去**。

## 三条翻译规则（其余都是机械映射）

1. **多候选 next** = Maa 的"按优先级试"。
   前面 n-1 个候选翻译成「条件成立就跳过去」，最后一个候选原地展开（fallback）。

2. **每个节点都带 jumpId**，等于原节点名。
   跳转目标是按名字找的，全都有标签就不会出现"跳不到"。

3. **回头边（环）** 直接翻译成跳转。
   比如 boss 关的喂豆节点 next 里包含它自己，翻译后就是"跳回自己"。

## 已知会变味的地方（都写进脚本的 desc 里）

- Maa 的 `timeout` 没有对应概念。识别不到就跳过，不会像 Maa 那样等。
- `target_offset`、`order_by`、`green_mask` 忽略。
- OCR 的 `expected` 在 Maa 里是正则，我们这边是"包含"匹配，正则符号会被剥掉。
- 颜色只支持 RGB（Maa 的 method=4）。HSV（method=40）会换算成 RGB 中心色，
  但区间不是矩形，换算后精度会掉。

用法：
    python maa2zjs.py <pipeline.json> <site.json> <image根目录> <输出.zjs> [源宽 源高]
"""

import base64
import json
import os
import re
import sys

# 源项目的逻辑分辨率。
#
# 依据：interface.json 里 display_short_side = 1080，而 YS.json + site.json 的
# 坐标上界是 x=1891 / y=1058 —— 正好落在 1920×1080 里（短边 1080，等于不缩放）。
# 换项目时用命令行参数覆盖，别硬套。
SRC_W = 1920
SRC_H = 1080

# 把「点文字」转成「点固定坐标 + 文字条件」。
# 用 --ocr-fixed-click 打开。适用于按钮位置固定的场景（用户确认过）。
OCR_FIXED_CLICK = False


def pct_x(v):
    return round(float(v) / SRC_W * 100.0, 2)


def pct_y(v):
    return round(float(v) / SRC_H * 100.0, 2)


def area_str(roi):
    """Maa 的 roi（x,y,w,h）→ 我们的 limitArea（左 上 右 下，百分比）。"""
    x, y, w, h = roi[0], roi[1], roi[2], roi[3]
    return f"{pct_x(x)}% {pct_y(y)}% {pct_x(x + w)}% {pct_y(y + h)}%"


def strip_regex(text):
    """Maa 的 expected 是正则，我们做包含匹配 —— 剥掉锚点和转义。"""
    t = text.replace(".*", "").replace("^", "").replace("$", "")
    t = re.sub(r"\\(.)", r"\1", t)
    return t.strip()


def parse_point(value):
    """
    把一个"点"解析成 (x, y)。

    支持三种写法（都在真实脚本里出现过）：
        "卡槽1"        → site.json 里的命名点
        "1801 182"     → 空格分隔的两个数
        [1801, 182]    → 数组
    """
    if isinstance(value, (list, tuple)):
        return float(value[0]), float(value[1])
    s = str(value).strip()
    parts = s.split()
    if len(parts) >= 2:
        try:
            return float(parts[0]), float(parts[1])
        except ValueError:
            pass
    raise ValueError(f"认不出的坐标写法: {value!r}（命名点要先在 site.json 里定义）")


def color_of(node):
    """Maa 的 lower/upper → 我们的一色 + 容差。返回 (r, g, b, 容差)。"""
    lo = node["lower"]
    hi = node["upper"]
    method = node.get("method", 4)

    if method == 40:
        # HSV：区间在色相上是环形的，用中心色近似。
        import colorsys
        h = (lo[0] + hi[0]) / 2.0 / 180.0     # OpenCV 的 H 是 0~180
        s = (lo[1] + hi[1]) / 2.0 / 255.0
        v = (lo[2] + hi[2]) / 2.0 / 255.0
        r, g, b = colorsys.hsv_to_rgb(h, s, v)
        r, g, b = int(r * 255), int(g * 255), int(b * 255)
        tol = max(hi[1] - lo[1], hi[2] - lo[2]) / 2
        return r, g, b, max(tol, 12)

    # RGB：逐通道取中心
    r = (lo[0] + hi[0]) // 2
    g = (lo[1] + hi[1]) // 2
    b = (lo[2] + hi[2]) // 2
    tol = max(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) / 2.0
    return r, g, b, max(tol, 8)


def sim_from_tolerance(tol):
    """容差（0~255）→ 相似度（0~100）。我们的颜色匹配就是按这个反着算的。"""
    return int(max(0, min(100, round(100 - tol * 100.0 / 255.0))))


class Converter:
    def __init__(self, nodes, site, image_root):
        self.nodes = nodes
        self.image_root = image_root
        self.points = self._build_points(site)
        self.actions = []
        self.order = []
        self._emitted = set()
        self._image_cache = {}

    # ---------------------------------------------------------------- 准备

    def _build_points(self, site):
        """site.json 里每个命名节点的 roi 中心就是那个点的坐标。"""
        pts = {}
        for name, node in site.items():
            rec = node.get("recognition")
            roi = None
            if isinstance(rec, dict):
                roi = rec.get("param", {}).get("roi")
            elif node.get("roi"):
                roi = node["roi"]
            if roi and len(roi) >= 4:
                pts[name] = (roi[0] + roi[2] / 2.0, roi[1] + roi[3] / 2.0)
            elif node.get("target"):
                t = node["target"]
                pts[name] = (t[0], t[1])
        return pts

    def point(self, ref):
        """命名点优先，其次当成数字串。"""
        if isinstance(ref, str) and ref in self.points:
            return self.points[ref]
        return parse_point(ref)

    def image_data(self, rel_path):
        """模板图 → 我们的 ImageData（base64 PNG + 尺寸 + 屏幕尺寸）。"""
        if rel_path in self._image_cache:
            return self._image_cache[rel_path]

        full = os.path.join(self.image_root, rel_path)
        if not os.path.isfile(full):
            raise FileNotFoundError(f"模板图不存在: {full}")

        with open(full, "rb") as f:
            raw = f.read()

        # PNG 头里直接读宽高，省得依赖 Pillow
        w = int.from_bytes(raw[16:20], "big")
        h = int.from_bytes(raw[20:24], "big")

        data = {
            "data": base64.b64encode(raw).decode("ascii"),
            "imageWidth": w,
            "imageHeight": h,
            "imageLeft": 0,
            "imageTop": 0,
            "screenWidth": SRC_W,
            "screenHeight": SRC_H,
        }
        self._image_cache[rel_path] = data
        return data

    # ---------------------------------------------------------------- 识别

    def pos_data(self, node):
        """节点的识别 → 我们的位置对象（posData）。DirectHit 返回 None。"""
        rec = self._rec_type(node)
        roi = node.get("roi")

        if rec == "TemplateMatch":
            pd = {"type": "image", "imageData": self.image_data(node["template"])}
            if roi:
                pd["limitArea"] = area_str(roi)
            thr = node.get("threshold")
            if thr is not None:
                pd["minSimilarPercent"] = int(round(float(thr) * 100))
            return pd

        if rec == "OCR":
            exp = node.get("expected")
            text = exp[0] if isinstance(exp, list) else exp
            pd = {"type": "text", "text": strip_regex(str(text))}
            if roi:
                pd["limitArea"] = area_str(roi)
            thr = node.get("threshold")
            # ★ 没写 threshold 时不能留空。
            #
            #   Maa 的 OCR threshold 默认 0.7（识别置信度）；我们的 similarPercent
            #   是**文字相似度**，默认 90%。两者语义不同，留空就等于用 90 去卡，
            #   比 Maa 严得多 —— 「开始战斗」出现在「开始战斗开始战斗」里
            #   （游戏按钮渲染了两遍）只有 0.85，直接判不成立。
            #
            #   这里落到 80：既比 90 宽松，又能拦住明显不对的文本。
            pd["similarPercent"] = (
                int(round(float(thr) * 100)) if thr is not None else 80
            )
            return pd

        if rec == "ColorMatch":
            r, g, b, tol = color_of(node)
            pd = {
                "type": "color",
                "color": f"{r:02X}{g:02X}{b:02X}",
                "similarPercent": sim_from_tolerance(tol),
            }
            if roi:
                pd["limitArea"] = area_str(roi)
            return pd

        return None

    @staticmethod
    def _timeout_ms(node):
        """
        Maa 的 timeout → 毫秒，**三态**。

            timeout > 0   → 那个毫秒数
            timeout == -1 → -1（一直等，只有停止能结束）
            timeout 缺失  → 20000（Maa 的默认值）

        ★ 这三个必须分开。之前把 -1 也写成 0，而执行器把 0 当"只试一次"，
          于是「死等结算画面」的节点变成「一帧没命中就跳过」；
          缺失的又写成 0，于是本该 20 秒放弃的变成无限等。方向正好相反的两种错。
        """
        t = node.get("timeout")
        if t is None:
            return 20000
        try:
            t = int(t)
        except (TypeError, ValueError):
            return 20000
        return -1 if t == -1 else max(0, t)

    def condition(self, node):
        """
        节点的识别 → 我们的 condition。DirectHit 返回 None。

        同时挂上重试字段（识别次数/间隔）：Maa 的语义是"等这个画面出现，
        最多 timeout 那么久，每 rate_limit 毫秒看一次"。降级成条件之后，
        这个等待只能由 condition 的重试来表达 —— 不写的话就变成"只判一次"，
        画面还没渲染好就走错分支。
        """
        pd = self.pos_data(node)
        if pd is None:
            return None
        t = pd["type"]
        if t == "image":
            cond = {"type": "image", "image": pd}
        elif t == "text":
            cond = {"type": "textFount", "text": pd}
        elif t == "color":
            cond = {"type": "colorFound", "colorData": pd}
        else:
            return None

        step = int(node.get("rate_limit") or 500)
        tmo = self._timeout_ms(node)
        cond["repeatWhenFalse"] = True
        # 0 = 无限（对应 timeout:-1）；否则换算成"额外检查次数"
        cond["repeatWhenFalseLimitTimes"] = 0 if tmo < 0 else max(1, tmo // max(step, 1))
        cond["repeatWhenFalseRepeatDelay"] = step
        return cond

    # ---------------------------------------------------------------- 动作

    @staticmethod
    def _next_list(node):
        """
        next 归一化成列表。

        Maa 里 `"next": "某节点"` 和 `"next": ["某节点"]` 都合法 —— 两种写法
        在不同作者的脚本里都出现过。直接 `for x in next` 会把字符串按字符拆开，
        转出来的东西毫无意义且不报错。
        """
        nxt = node.get("next")
        if nxt is None:
            return []
        if isinstance(nxt, str):
            return [nxt] if nxt else []
        return list(nxt)

    @staticmethod
    def _rec_type(node):
        rec = node.get("recognition")
        if isinstance(rec, dict):
            return rec.get("type", "DirectHit")
        return rec or "DirectHit"

    def action_type(self, node):
        # 动作名大小写都收：同一个项目里 `Click` 和 `click` 混着写
        act = str(node.get("action", "DoNothing")).lower()
        rec = self._rec_type(node)

        if act == "swipe":
            return "滑动"
        if act == "click":
            if node.get("target"):
                return "点击"
            if rec == "TemplateMatch":
                return "点击图片"
            if rec == "OCR":
                return "点击文字"
            if rec == "ColorMatch":
                return "点击颜色"
            return "点击"
        # DoNothing / 其他：发一个 0ms 的等待，只为了有个地方挂 jumpId
        return "控制执行"

    def ocr_click_as_fixed(self, node, delay_ms):
        """
        「点文字」→「点固定坐标 + 文字条件」。

        什么时候用：按钮位置固定（用户确认过），OCR 只是用来确认"到了这个界面"。
        好处是识别只当闸门，点的是硬坐标 —— 不受字体渲染/OCR 误识影响。
        PP-OCR 读不动游戏里的艺术字（实测把「开始战斗」读成「HN」），
        所以固定位置的按钮用这条路径更稳。

        条件上带识别次数/间隔（repeatWhenFalse + limit + delay），
        这些是自动精灵的原生字段，界面上也能直接改。
        """
        roi = node.get("roi")
        if not roi or len(roi) < 4:
            return None
        cx = roi[0] + roi[2] / 2.0
        cy = roi[1] + roi[3] / 2.0
        cond = self.condition(node)
        if cond:
            cond = dict(cond)
            cond["repeatWhenFalse"] = True
            # 0 = 无限次（和 Maa 的 timeout:-1 对应）；有限次数就填次数
            timeout = node.get("timeout")
            cond["repeatWhenFalseLimitTimes"] = 0 if timeout == -1 or not timeout else max(
                1, int(timeout) // int(node.get("rate_limit") or 500)
            )
            cond["repeatWhenFalseRepeatDelay"] = int(node.get("rate_limit") or 500)
        return {
            "type": "点击",
            "desc": node["name"],
            "jumpId": node["name"],
            "delay": str(delay_ms),
            "delayUnit": 0,
            "repeatCount": 1,
            "posData": {"type": "location", "x": f"{pct_x(cx)}%", "y": f"{pct_y(cy)}%"},
            **({"condition": cond} if cond else {}),
        }

    def build_action(self, node, delay_ms):
        name = node["name"]

        # OCR 点击 → 固定坐标点击（按钮位置固定时更稳）
        if OCR_FIXED_CLICK and self._rec_type(node) == "OCR" and \
                str(node.get("action", "")).lower() == "click" and not node.get("target"):
            fixed = self.ocr_click_as_fixed(node, delay_ms)
            if fixed:
                return fixed

        atype = self.action_type(node)
        a = {
            "type": atype,
            "desc": name,
            "jumpId": name,
            "delay": str(delay_ms),
            "delayUnit": 0,
            "repeatCount": node.get("repeat", 1),
        }

        if node.get("rate_limit"):
            a["repeatDelayValue"] = str(node["rate_limit"])
            a["repeatDelayUnit"] = 0

        dur = node.get("duration")
        if dur:
            a["duration"] = str(dur)

        if atype == "控制执行":
            a["controlRunType"] = "waitDelay"
            # DoNothing 节点在 Maa 里也可能带识别：它就是个闸门。
            cond = self.condition(node)
            if cond:
                a["condition"] = cond
            return a

        if atype == "滑动":
            sx, sy = self.point(node["begin"])
            ex, ey = self.point(node["end"])
            a["startPos"] = {"type": "location", "x": f"{pct_x(sx)}%", "y": f"{pct_y(sy)}%"}
            a["endPos"] = {"type": "location", "x": f"{pct_x(ex)}%", "y": f"{pct_y(ey)}%"}
            # ★ 滑动上的识别不能丢。
            #
            #   刷金币这个脚本里 17 个颜色识别全挂在滑动上（"这个格子有颜色
            #   才往这里种"）。只给点击加条件的话，这些闸门会被静默丢掉，
            #   脚本就变成无条件乱滑 —— 看着在跑，种的全是错位置。
            cond = self.condition(node)
            if cond:
                a["condition"] = cond
            return a

        # 点击系列
        target = node.get("target")
        if target:
            # Maa 的 target 是"点这里，而不是点识别到的位置"
            if len(target) >= 4:
                cx = target[0] + target[2] / 2.0
                cy = target[1] + target[3] / 2.0
            else:
                cx, cy = target[0], target[1]
            a["posData"] = {"type": "location", "x": f"{pct_x(cx)}%", "y": f"{pct_y(cy)}%"}
            # 有 target 时识别只当门闸，写成条件
            cond = self.condition(node)
            if cond:
                a["condition"] = cond
            return a

        pd = self.pos_data(node)
        if pd:
            a["posData"] = pd
        return a

    def jump_action(self, target_name, delay_ms=0):
        return {
            "type": "控制执行",
            "controlRunType": "jumpTo",
            "jumpToPosition": target_name,
            "desc": f"→ {target_name}",
            "delay": str(delay_ms),
            "delayUnit": 0,
            "repeatCount": 1,
        }

    # ---------------------------------------------------------------- 展开

    def wait_action(self, node, nxt, delay_ms=0):
        """
        Maa 的 `next` → 我们的「等待候选」。

        这是翻译里最关键的一步。Maa 的 next 是**候选链**：每轮按顺序逐个识别，
        第一个成功的被进入，都不成功就继续等，直到超时。

        用「条件 + 跳转」拼能凑出分支，但凑不出**等待** —— 条件不满足就跳过，
        脚本会一路冲过去，不等画面。所以这里发一条等待候选，轮询交给执行器。
        """
        timeout = node.get("timeout")
        if isinstance(timeout, int) and timeout > 0:
            tmo = timeout
        else:
            # -1 或未写 = 无限等。候选里通常有 DirectHit 兜底，一定会命中，
            # 所以这个 0 不会真的死等。
            tmo = 0

        cands = []
        for cand in nxt:
            cnode = self.nodes.get(cand, {}) or {}
            cond = self.condition(cnode)
            c = {"jumpTo": cand}
            if cond:
                c["condition"] = cond
            cands.append(c)

        return {
            "type": "等待候选",
            "desc": f"{node['name']} 等候选",
            # ★ delay 必须是累积的 post_delay，不能写死 0。
            #
            #   自环节点（boss 喂豆 → 自己）在 Maa 里靠 post_delay=3000 控制
            #   每次喂豆的间隔。写死 0 的话这条环会**全速空转**：
            #   日志刷屏、CPU 打满、每秒往游戏里灌几十次滑动。
            "delay": str(delay_ms),
            "delayUnit": 0,
            "repeatCount": 1,
            "waitTimeoutMs": self._timeout_ms(node),
            "waitIntervalMs": int(node.get("rate_limit") or 200),
            "candidates": cands,
        }

    def collect_order(self):
        """
        从入口出发，按 next 顺序深度优先走一遍，每个节点只记一次。
        重复出现的节点不再展开 —— 它已经有 jumpId，运行时用跳转回去。
        """
        entry = next(iter(self.nodes))
        stack = [entry]
        while stack:
            name = stack.pop()
            if name in self._emitted or name not in self.nodes:
                continue
            self._emitted.add(name)
            self.order.append(name)
            nxt = self._next_list(self.nodes[name])
            # 反向压栈，保证按 next 顺序展开
            for child in reversed(nxt):
                if child not in self._emitted:
                    stack.append(child)

    def emit(self):
        self.collect_order()
        carried = 0  # 上一个节点的 post_delay

        for idx, name in enumerate(self.order):
            node = self.nodes[name]
            node = dict(node)
            node["name"] = name

            delay = carried + int(node.get("pre_delay") or 0)
            carried = int(node.get("post_delay") or 0)

            act = self.build_action(node, delay)

            # Maa 的 timeout / rate_limit → 我们的等待参数。
            #
            # 有识别的动作才需要等：固定坐标的点击等没有意义。
            # timeout == -1 或没写都当作"一直等"（Maa 的 timeout 到点才放弃，
            # 而 -1 就是永不放弃）。没有这个字段，我们的脚本会一路冲过去，
            # 不等画面出现 —— 那正是和 Maa 差的那一块。
            has_rec = self._rec_type(node) in ("TemplateMatch", "OCR", "ColorMatch")
            if has_rec:
                # 三态统一由 _timeout_ms 表达：正数 / -1（一直等）/ 缺省 20000。
                # 之前把 -1 写成 0，而执行器把 0 读成"只试一次"——
                # "死等结算画面"就变成了"一帧没命中立刻跳过"，方向完全反了。
                act["waitTimeoutMs"] = self._timeout_ms(node)
            if node.get("rate_limit"):
                act["waitIntervalMs"] = int(node["rate_limit"])

            self.actions.append(act)

            nxt = self._next_list(node)
            if not nxt:
                continue

            if len(nxt) == 1:
                # 下一个节点刚好就是列表里的下一条 → 让它自然往下走
                if idx + 1 < len(self.order) and self.order[idx + 1] == nxt[0]:
                    continue
                self.actions.append(self.jump_action(nxt[0]))
                continue

            # 多候选 → 一条「等待候选」。由执行器按顺序轮询，语义和 Maa 一致。
            # 把 post_delay 交给它：候选命中后会立刻跳走，间隔只能挂在这里。
            self.actions.append(self.wait_action(node, nxt, carried))
            carried = 0

        return self.actions


def strip_jsonc(text):
    """
    去掉 JSON 里的 // 和 /* */ 注释，以及尾随逗号。

    Maa 的 pipeline 是 JSONC：允许注释（甚至有人用注释来"关掉"某个候选节点，
    比如 YS.json 里 `//"神器补给_神器",`）。标准 json 模块会直接报错，
    所以先洗一遍。

    必须在**字符串外**才处理，否则 URL 里的 // 会被吃掉。
    """
    out = []
    i = 0
    n = len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue

        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue

        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] not in "\r\n":
                i += 1
            continue

        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue

        out.append(c)
        i += 1

    cleaned = "".join(out)
    # 尾随逗号：,] 或 ,}
    cleaned = re.sub(r",(\s*[\]}])", r"\1", cleaned)
    return cleaned


def load_jsonc(path):
    with open(path, encoding="utf-8-sig") as f:
        return json.loads(strip_jsonc(f.read()))


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(1)

    global OCR_FIXED_CLICK
    if "--ocr-fixed-click" in sys.argv:
        OCR_FIXED_CLICK = True
        sys.argv = [a for a in sys.argv if a != "--ocr-fixed-click"]

    pipeline_path, site_path, image_root, out_path = sys.argv[1:5]
    if len(sys.argv) >= 7:
        global SRC_W, SRC_H
        SRC_W, SRC_H = int(sys.argv[5]), int(sys.argv[6])

    nodes = load_jsonc(pipeline_path)
    site = load_jsonc(site_path)

    conv = Converter(nodes, site, image_root)
    actions = conv.emit()

    base = {
        "repeatCount": 1,
        "delay": "0",
        "delayUnit": 0,
        "pauseOnFail": True,
        "screenWidth": 1280,
        "screenHeight": 720,
        "description": f"由 MaaFramework 脚本转换：{os.path.basename(pipeline_path)}",
        "minVerName": "26.0.0",
        "minVerCode": 260000,
        "count": len(actions),
        "savedVerName": "26.0.0",
        "savedVerCode": 260000,
    }

    # NDJSON：第一行 baseInfo，之后一行一个动作
    with open(out_path, "w", encoding="utf-8", newline="\r\n") as f:
        f.write(json.dumps(base, ensure_ascii=False, separators=(",", ":")) + "\n")
        for a in actions:
            f.write(json.dumps(a, ensure_ascii=False, separators=(",", ":")) + "\n")

    print(f"节点 {len(nodes)} 个 → 动作 {len(actions)} 条")
    print(f"输出: {out_path}")


if __name__ == "__main__":
    main()
