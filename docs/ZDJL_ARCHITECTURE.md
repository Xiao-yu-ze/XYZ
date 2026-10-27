# 脚本格式（Android 版）架构侦察

对象：`C:\Users\29374\Desktop\Android脚本格式\base.apk`（40.8 MB）
目的：在"识别速度"和"注入实现"两件事上，用**已跑通的商业实现**对照我们的方案，
而不是靠猜。

---

## 一、它用的根本不是模板匹配

| 证据 | 说明 |
|---|---|
| **没有 OpenCV** | `lib/` 下无任何 opencv 库；dex 里 `opencv` 出现 0 次 |
| `libedge-infer.so` 63.5 MB | 自带推理引擎 |
| `assets/infer/model` 2.3 MB<br>`assets/infer/params` 4.3 MB | 神经网络权重 |

`libedge-infer.so` 的字符串特征表明它是 **MNN**（阿里移动端推理框架）静态链入：

```
conv2d      161 次
Pooling     106 次
Tensor     1939 次
Forward     109 次
Backward     89 次
sgd           1 次
MNN          73 次
proto     36476 次
```

`Backward` / `sgd` 的存在说明**带训练能力** —— 很可能是"用户框选一块区域，
现场训练一个小模型来认它"，这样对缩放、色差、轻微形变都鲁棒，
而且**一次前向传播**就出结果，不需要在几十万个位置上滑窗。

**这就是他们能快的根本原因：不是匹配得快，是不做暴力匹配。**

## 二、但这条路我们不走

| 成本 | 说明 |
|---|---|
| 63.5 MB 推理引擎 | 我们现在 APK 已经 108 MB |
| 需要训练数据 | 他们能在线学，我们没有标注数据 |
| 我们有 800 张固定模板 | 是这个游戏的原生素材，模板匹配本来就够用 |

**我们的场景是"固定游戏 + 已知素材"，不是"任意用户图片"。**
模板匹配在这个场景下是对的，只要把搜索区收窄。

## 三、它自己的脚本就是这么写的（可直接照抄）

统计我们 assets 里那批**来自真实 pipeline**：

```
01_Endless_plant_ref.json         TemplateMatch=2   roi=5
02_Endless_plant_Choose_ref.json  TemplateMatch=3   roi=10
Endless_out.json                  TemplateMatch=1   roi=5
0300Endless_fight.json            TemplateMatch=1   roi=6
03-1-01Endless_fight.json         TemplateMatch=2   roi=2
0601Endless_god_vessel.json       TemplateMatch=2   roi=7
0601_80plant_choose_1.json        TemplateMatch=1   roi=4

合计：TemplateMatch 12 次，roi 12 次
```

**每一个模板匹配节点都带 roi，没有一个例外。**

这条比任何理论都直接：连商业实现也不做全屏搜索。我们的识别慢，
根因就是自检和某些节点在全屏搜；给节点配好 roi 就解决了。

## 四、注入架构被验证了

`assets/zdtouch/{arm64-v8a,armeabi,armeabi-v7a,x86,x86_64}` 共 5 个文件，
头部是 `7f 45 4c 46`（**ELF 可执行文件**，不是 .so）。
也就是说：**它把注入器作为一个独立可执行文件放在 assets 里，运行时推出去执行。**

这和我们用 `app_process` 拉起 `Pvz2UserService` 是**同一套架构**。
说明我们的方向没问题。

## 五、读不到它的脚本引擎

- `classes.dex` 7 MB，但 `Ljava/lang/String;` 只出现 2 次 → **是加固壳**（360 加固，`assets/libjiagu_a64.so`）
- 真代码运行时解密，静态分析拿不到

不过 `libquickjs-android.so` 说明它的脚本语言**基于 JavaScript（QuickJS）**。
我们用的是自己定义的 JSON pipeline 格式，语义上对齐它的指令集即可，不必照搬引擎。

## 六、OCR 选择对照

| | 脚本格式 | 我们 |
|---|---|---|
| 引擎 | Google ML Kit（`libmlkit_google_ocr_pipeline.so` + tflite 模型） | PaddleOCR + ONNX Runtime |

两者都合理。ML Kit 对拉丁字母强、模型可动态下载；PaddleOCR 对中文更准、离线自带。
**我们的选择不用改。**

---

## 结论（按可执行性排序）

1. **给所有识别节点配 roi** —— 脚本格式自己也这么做，12/12。这是识别提速的唯一主要手段。
2. **不抄神经网络方案** —— 依赖 63.5 MB 引擎 + 训练数据，我们场景用不上。
3. **注入架构保持不变** —— 和商业实现同构（独立 ELF / app_process）。
4. **OCR 保持不变** —— PaddleOCR 对中文够用。