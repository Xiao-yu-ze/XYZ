# 自动精灵怎么截图、怎么框 ROI

来源：`_probe/zdjl-re/extracted/dist-js/` 里反编译出的渲染层 bundle
（`renderer-xm`、`renderer-home`、`renderer-script-run-area`）。
下列函数名、字段名、调用链都是**原文里有的**，不是推测。

---

## 一、它没有"先截图再编辑"这一步

关键差异：**所有取图、取色、取坐标、框范围，都在正在运行的那块实时画面上完成**。

它的那块画面叫「运行区域」（script run area），有一个 `scriptRunAreaManager`
统一管理，靠一个"当前状态"（current-status）字段决定画面上现在在干什么：

```js
scriptRunAreaManager.checkCanEnterStatus(threadId, "previewResultBounds")
scriptRunAreaManager.clearCurrentStatus(threadId)
window.APPMethods.scriptRunAreaManager.addEventListener(
  `current-status-changed-${threadId}`, ...)
```

进入某个状态时，画面上弹一个提示框「请在脚本运行区域中完成选择」，
用户操作完 → `clearCurrentStatus` → 值回填字段。

**为什么这样可以**：用户不用在"看"和"改"之间来回切页面。
他看到的画面就是程序要识别的那个画面。

---

## 二、截模板图

字段组件（`45643` 模块）的 onClick：

```js
const r = await window.APPMethods.scriptRunAreaManager.chooseImage(threadId, initial)
if (r) {
  const bound = await window.APPMethods.scriptRunAreaManager.getOverlayBound(threadId)
  img.data         = r.imageData          // base64 PNG
  img.imageWidth   = r.imageWidth
  img.imageHeight  = r.imageHeight
  img.imageLeft    = ZValue.valueOf(r.area.left).getValueInDp(bound.width)
  img.imageTop     = ZValue.valueOf(r.area.top).getValueInDp(bound.height)
  img.screenWidth  = bound.width
  img.screenHeight = bound.height
}
```

要点：

1. `chooseImage` 返回的是**已经裁好的图**（`imageData`）+ 它在画面上的位置（`area`）
2. `area` 是百分比（`left/top/right/bottom`）
3. 写进脚本时 `imageLeft/imageTop` 要**换算成 dp**（`getValueInDp`）
4. `screenWidth/screenHeight` 取自 `getOverlayBound` —— **画面实际尺寸，不是写死的**

所以一张模板带 6 个尺寸字段，是为了在别的分辨率下还原位置。

## 三、框识别范围（limitArea）

两个入口都有：

**(a) 四个数字框**（`30327` 模块）：`左 / 右 / 上 / 下`，`label:"左"`、`xOrY:"x"`…
拼成 `ZArea(left, top, right, bottom)`。注意顺序是**左 上 右 下**。

**(b) 在画面上拖框**（`defaultArea` + `onAreaChange`）：

```js
defaultArea: {left: l, top: h, right: m, bottom: E}
onAreaChange: e => n(new ZArea(e.left, e.top, e.right, e.bottom))
```

拖完把四个值回填给数字框 —— **两条路写同一个字段，不是两套数据**。

## 四、把匹配结果画回画面

这是它体验上最关键的一点（`7077` 模块 `D()`）：

```js
const e = (await s.limitAreaVarField.evalOrFallback(c))?.getBoundInDp(await ...)
return {
  limitArea: e && e.right && e.bottom
    ? {x: e.left||0, y: e.top||0, width: e.right-(e.left||0), height: e.bottom-(e.top||0)}
    : undefined,
  results: n.map(e => ({
    label: t instanceof Text ? `文字：${e.label}` : `相似度: ${(100*e.similar).toFixed(2)}%`,
    rect: {x: e.boundLeft, y: e.boundTop,
           width: e.boundRight - e.boundLeft, height: e.boundBottom - e.boundTop}
  }))
}
await window.APPMethods.scriptRunAreaManager.previewResultBounds(threadId, ...)
```

然后 **300ms 轮询**一次，只在与上次不同时才重发（`Z.ZN` 比较）：

```js
const I = setInterval(w, 300)
```

所以用户调阈值/改范围时，画面上**实时**出现命中框和相似度，不用点"测试运行"。

还有 `previewFilter`：颜色识别的"预览滤镜效果"走同一套，把
`changeColor` 规则实时应用到画面上，让用户看到**过滤之后**的图长什么样。

---

## 五、对我们（PVZ2 自动精灵兼容层）的结论

| 自动精灵的做法 | 我们的对应 | 状态 |
|---|---|---|
| 实时画面上取点 | `PreviewPicker` POINT + 准星 | ✅ 已实现 |
| 实时画面上框 ROI | `PreviewPicker` RECT → 百分比 | ✅ 已实现 |
| 实时画面上截模板 | `PreviewPicker` TEMPLATE → 裁图 + 6 字段 | ✅ 已实现 |
| 匹配结果画回画面 | 未做 | ⬜ 待做 |
| 300ms 轮询实时预览 | 未做 | ⬜ 待做 |
| 颜色滤镜预览 | 未做（颜色识别本身也没接） | ⬜ 待做 |

**截图机制上我们比它简单**：它要面对"别人的画面"（MediaProjection 之类，
有权限和遮挡问题）；我们自己持有虚拟屏的 `Surface`，
`ImageReader` 直接拿帧，30fps，没有授权弹窗，也不会有别的东西盖住。

模板数据的落盘格式两边一致，因为都按 `.zjs` 走 —— 我们在自动精灵里
存过的模板能读回来，反之亦然。
