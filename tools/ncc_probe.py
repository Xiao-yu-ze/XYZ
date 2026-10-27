"""
在 PC 上复用 参考管线 的模板，对真实游戏截图算 TM_CCOEFF_NORMED 分数。

为什么要在 PC 上做：Android 那边触发识别自检需要点 App 里的按钮，一点就抢前台，
screencap 截到的是 App 自己 —— 之前那些低分就是这么来的。用 adb 直接
`am startservice` 又被 Android 12 的后台启动限制挡住（`app is in background uid null`）。
所以在 PC 上跑同样算法，绕开整个触发问题，而且验证更干净。

算法与 app 内一致：
  R = Σ(T'·I') / sqrt(ΣT'² · ΣI'²)，T'=T-mean(T)，I'=I_window-mean(I_window)
交叉项用 FFT 做线性卷积，窗口和用积分图（cumsum），与 OpenCV 的实现思路相同。

差异（已知，见输出里的 caveat）：
  · 灰度而非 4 通道 —— app 是 CV_8UC4，这里降到亮度。对"能不能匹配上"这个判断够用。
  · 不做缩放 —— 截图与模板同为 720p 基准，scale=1.0。
"""
import sys
import os
import numpy as np
from PIL import Image

# 从脚本位置推导：tools/ncc_probe.py -> <repo>/app/src/main/assets
_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE) if os.path.basename(_HERE) == "tools" else _HERE
ASSETS = os.path.join(_REPO, "app", "src", "main", "assets")

# 无尽挑战流程实际引用的 11 个模板（路径相对 assets 根，与 pipeline JSON 一致）
TEMPLATES = [
    "create_yard/竞技.png",
    "Endless/无尽挑战.png",
    "General/能量豆.png",
    "General/暂停.png",
    "Endless/frame/boss/僵王的头.png",
    "Endless/frame/boss/功夫僵王.png",
    "Endless/frame/僵尸头像.png",
    "Endless/继续挑战_恭喜过关.png",
    "Endless/out.png",
    "Endless/yes.png",
    "Start_game/叉叉.png",
]


def load_gray(path):
    im = Image.open(path)
    # 转 RGBA 再取亮度；alpha 忽略（app 里 alpha 恒为 255 对帧，模板的 alpha 这里不参与）
    im = im.convert("RGBA")
    arr = np.asarray(im, dtype=np.float64)
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    return 0.299 * r + 0.587 * g + 0.114 * b


def box_sum(integral, h, w, oh, ow):
    """模板尺寸 (h,w) 的窗口和，输出 (oh,ow)。integral 已 pad 1。"""
    return (integral[h:h + oh, w:w + ow]
            - integral[0:oh,     w:w + ow]
            - integral[h:h + oh, 0:ow]
            + integral[0:oh,     0:ow])


def match_ccoeff_normed(img, templ):
    """返回 (最大分数, (x, y))。img 与 templ 均为 2D float。"""
    H, W = img.shape
    h, w = templ.shape
    if h > H or w > W:
        return -1.0, (-1, -1)

    oh, ow = H - h + 1, W - w + 1
    n = float(h * w)

    # 窗口和 / 窗口平方和 → 局部均值与局部方差
    ii = np.pad(img.cumsum(0).cumsum(1), ((1, 0), (1, 0)))
    ii2 = np.pad((img * img).cumsum(0).cumsum(1), ((1, 0), (1, 0)))
    S1 = box_sum(ii,  h, w, oh, ow)
    S2 = box_sum(ii2, h, w, oh, ow)
    mean_i = S1 / n

    # 交叉项：img 与翻转后的 templ 做线性卷积
    # conv[y+h-1, x+w-1] = Σ img[y+i, x+j] · templ[i, j]
    Fa = np.fft.rfft2(img)
    Fb = np.fft.rfft2(templ[::-1, ::-1], s=img.shape)
    conv = np.fft.irfft2(Fa * Fb, s=img.shape)
    cross = conv[h - 1:h - 1 + oh, w - 1:w - 1 + ow]

    mean_t = templ.mean()
    sum_t2 = float((templ * templ).sum())

    num = cross - n * mean_t * mean_i
    var_t = sum_t2 - n * mean_t * mean_t
    var_i = S2 - n * mean_i * mean_i
    den = np.sqrt(np.maximum(var_t, 1e-12) * np.maximum(var_i, 1e-12))

    R = num / den
    idx = int(np.argmax(R))
    y, x = divmod(idx, ow)
    return float(R[y, x]), (x, y)


def main():
    frame_path = sys.argv[1] if len(sys.argv) > 1 \
        else os.path.join(_REPO, "_probe", "vframe.png")

    img = load_gray(frame_path)
    print("frame: %s  %dx%d" % (os.path.basename(frame_path), img.shape[1], img.shape[0]))
    print("=" * 78)

    results = []
    for rel in TEMPLATES:
        path = os.path.join(ASSETS, rel)
        if not os.path.exists(path):
            print("%-44s  [缺文件]" % rel)
            continue
        t = load_gray(path)
        score, (x, y) = match_ccoeff_normed(img, t)
        results.append((score, rel, x, y, t.shape[1], t.shape[0]))

    results.sort(reverse=True)
    print("%-44s %8s  %-14s %s" % ("模板", "分数", "位置", "模板尺寸"))
    print("-" * 78)
    for score, rel, x, y, tw, th in results:
        verdict = ("强" if score >= 0.90 else
                   "可用" if score >= 0.80 else
                   "偏弱" if score >= 0.70 else "未命中")
        print("%-44s %8.4f  (%4d,%4d)   %dx%d  %s" % (rel, score, x, y, tw, th, verdict))
    print("=" * 78)
    best = results[0]
    print("最高: %s = %.4f" % (best[1], best[0]))


if __name__ == "__main__":
    main()
