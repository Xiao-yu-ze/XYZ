# 项目状态（跨会话交接，读这一份就够）

## 设备与运行
- 设备 LENOVO TB323FU（代号 HA2J8Y8G），adb: D:\sdk\platform-tools\adb.exe
- 构建：JAVA_HOME=C:\pvz2auto\_toolchain\jdk17\jdk-17.0.20.1+1
  gradle: .\_toolchain\gradle-8.13\gradle-8.13\bin\gradle.bat :app:testDebugUnitTest :app:assembleDebug
- 跑脚本（无头）：run-as com.pvz2.auto am start-foreground-service --user 0 -n com.pvz2.auto/.service.AutomationService -a com.pvz2.auto.ZJS_RUN --es zjs_path <path>
- 停止：同上 -a com.pvz2.auto.ZJS_STOP
- 建屏入口是「在虚拟屏启动游戏」（ACTION_VD_START）；「启动（Shizuku）」只连服务，不建屏
- 虚拟屏固定 60Hz（setRequestedRefreshRate 被系统忽略），帧率下拉已删
- 脚本目录：/sdcard/Android/data/com.pvz2.auto/files/zjs/

## 已完成（近期）
- 心塔二号阵 5 个脚本：第1关(20) / 第2关(26) / 第3关起(16, 用户自己调的时序+点加速) / 补给(2) / BOSS关(21)
- 引擎：调用子链(CALL_SUB)、条件 timeoutMs、varEquals 条件、inputOptions 下拉、进度真实上报
- 编辑器：拖动时长/识别等待/之后等待 三行；点波「一轮约 N 秒」只读换算
- 采集：帧拷贝移出 ImageReader 回调（卡顿改善，丢帧 0）
- 补给节点判据修正：roi 723,56,17,22 + #9CDF10；优先级 全部>橙>蓝>神器>紫
- pvzNeedColor 已彻底删除（改用条件 colorFound）
- 仓库已公开：https://github.com/Xiao-yu-ze/XYZ，README 重写，节点字段总清单 docs/NODES_AND_FIELDS.md

## 进行中（下一步就做这个）
变量 → 节点参数 接线（当前下拉选了不生效）：
1. 主脚本加 JS 节点：把 心塔_小关喂豆间隔("7秒") → 心塔_点波次数 = round((ms-400)/100)；
   把 心塔_boss喂豆间隔("6秒") → 心塔_boss喂豆间隔ms = ms
2. BOSS 脚本 喂豆电大4 的 delay 改 __vars 引用 心塔_boss喂豆间隔ms
3. 点波节点 pvzWaveTimes 改 __vars 引用 心塔_点波次数
4. 真机跑一次 BOSS，日志确认「等待 5000ms」「第 66/66 次」

## 待办
- 断网处理：不做（用户决定）
- 字段分组折叠（基础/时机/识别/高级）+ 帮助写关联影响
- boss 后续：boss 关抛花/瓷砖用格子（已按格子写），补给已修但未实测
- 心塔：后期种蓓蕾 变量未接线；是否缺豆 判定未做（无条件种飞莲）

## 铁律
- 用户改过的脚本不要覆盖：先 pull → 只改该改的 → push → 再 pull 核对
- 不猜：结论要有日志/代码证据
- 加新节点/字段前先查 docs/NODES_AND_FIELDS.md，避免重复造轮子