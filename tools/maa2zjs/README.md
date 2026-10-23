# maa2zjs —— MaaFramework pipeline → 自动精灵 .zjs

## 用法

```bash
python tools/maa2zjs/maa2zjs.py \
    <pipeline.json> <site.json> <image 根目录> <输出.zjs> [源宽 源高]
```

例（无尽挑战）：

```bash
python tools/maa2zjs/maa2zjs.py \
    "C:/Users/29374/Desktop/mpz/assets/resource/pipeline/YS/YS.json" \
    "C:/Users/29374/Desktop/mpz/assets/resource/pipeline/Initialization/site.json" \
    "C:/Users/29374/Desktop/mpz/assets/resource/image" \
    "tools/maa2zjs/out/YS_无尽挑战.json" 1920 1080
```

`源宽/源高` 不填默认 1920×1080，见下面「坐标」。

## 两个模型的差别

```
Maa        一棵节点图。每次 tick 从当前节点按 next 顺序逐个试，
           第一个识别成功的节点被进入，执行 action，再继续。
           识别不到就等（timeout）。

自动精灵    一条线性列表。条件不满足就跳过这一条、往下走；
           要跳转就显式写「控制执行 → 跳转」。
```

所以转换的核心是**把图展开成线性列表，再用「条件 + 跳转」把分支补回去**。

## 映射规则

| Maa | 我们 | 说明 |
|---|---|---|
| `action: Swipe` + `begin/end` | `滑动` | 命名点 → 百分比坐标 |
| `action: Click` + `target` | `点击` | target 是固定点/矩形中心；识别降级成 `condition` |
| `action: Click` + 无 target | `点击图片/文字/颜色` | 点识别到的位置 |
| `action: DoNothing` | `控制执行(waitDelay,0ms)` | 只为挂 jumpId |
| `recognition: TemplateMatch` | `position: image` | base64 PNG 直接内嵌 |
| `recognition: OCR` | `position: text` | `expected` 去正则后做包含匹配 |
| `recognition: ColorMatch` | `position: color` | RGB 区间 → 中心色 + 相似度 |
| `next: [A, B, C]` | A、B 生成「条件成立就跳走」，C 原地展开 | Maa 的优先级语义 |
| 回头边 / 环 | 跳转 | 每个节点都有 `jumpId`，目标是节点名 |
| `repeat: N` | `repeatCount: N` | |
| `rate_limit: ms` | `repeatDelayValue: ms` | |
| `pre_delay` / `post_delay` | `delay` | post 累加到**下一条**的 delay |
| `duration` | `duration` | 按住/滑动时长 |

## 坐标

源项目的坐标是**逻辑分辨率**下的像素（`interface.json` 里
`display_short_side = 1080`，而实测坐标上界 x=1891 / y=1058，落在 1920×1080 里），
转换时全部换成**百分比**。百分比跟我们自己的虚拟屏分辨率无关，
换设备、换虚拟屏尺寸都不用重转。

`site.json` 里每个命名节点（`卡槽1`、`1-1`、`豆槽`…）的 `roi` 中心就是那个点。

## 会变味的地方

这些在原脚本里成立、转过之后不成立的，都记在这里，不要当成 bug：

1. **没有 timeout**。Maa 识别不到会一直等到超时（`timeout: -1` 是无限等），
   我们这边识别不到就跳过这一条。所以我们的流程可能比 Maa "跑得更快、更容易漏步"。
2. **OCR 的正则变包含匹配**。`expected: "继续挑战.*"` 之类会被剥成正文字符串。
3. **颜色只支持 RGB 矩形区间**。Maa 的 `method: 40`（HSV）是环形区间，
   换算成中心色 + 容差会掉精度。
4. **`target_offset` / `order_by` / `green_mask` 忽略**。
5. **注释掉的候选节点不转换**（Maa 里用 `//` 关掉的节点本来就不参与）。

## 校验

转换完建议跑一遍：

- 动作类型是否都在执行器支持列表里
- 每条 `jumpToPosition` 是否有对应的 `jumpId`
- 导入后编辑器摘要里有没有 `⚠` 校验提示

`out/YS_无尽挑战.json`：108 个节点 → 133 条动作，96 个跳转标签、37 条跳转全部有目标。
