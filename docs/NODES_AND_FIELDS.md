# 节点与字段总清单（加新东西前先读这个）

**规矩**：新增任何节点/字段前，先在这里搜一遍，确认不是重复造轮子。
尤其是「某条件下才执行」——一律用**条件字段**，不要再往 extra 里塞私有判据。

## 一、动作类型（49 个枚举 / 37 个 wire 名）

| 枚举 | wire 名 | 可执行 |
|---|---|---|
| LOCATION | location | — |
| COLOR | color | — |
| CLICK | 点击 | ✅ |
| CLICK_IMAGE | 点击图片 | ✅ |
| CLICK_TEXT | 点击文字 | ✅ |
| CLICK_COLOR | 点击颜色 | ✅ |
| SWIPE | 滑动 | ✅ |
| GESTURE | 单指手势 | ✅ |
| MULTI_SWIPE | 多指手势 | ✅ |
| KEY | 按键 | ✅ |
| INPUT_TEXT | 输入内容 | ✅ |
| RECOGNIZE_SCREEN | 识别屏幕 | ✅ |
| RUN_SCRIPT | 执行脚本 | ✅ |
| RUN_MULTI | 运行多个动作 | ✅ |
| CALL_SUB | 调用子链 | ✅ |
| PROMPT | 系统提示 | ✅ |
| SET_VARS | 设置变量 | ✅ |
| RUN_JS | 运行JS代码 | ✅ |
| WAIT_CANDIDATES | 等待候选 | ✅ |
| PVZ_PLANT | 种植物 | ✅ |
| PVZ_FEED | 喂豆 | ✅ |
| PVZ_SHOVEL | 铲除 | ✅ |
| PVZ_TAP_CELL | 点击格子 | ✅ |
| PVZ_ARTIFACT | 使用神器 | ✅ |
| PVZ_WAVE | 点下一波 | ✅ |
| PVZ_SPEED | 点加速 | ✅ |
| PVZ_SWITCH_DECK | 切换编队 | ✅ |
| PVZ_SUPPLY | 选补给 | ✅ |
| PVZ_START_BATTLE | 开始战斗 | ✅ |
| PVZ_ENTER_CHECK | 进关判定 | ✅ |
| PVZ_SETTLE | 识别结算 | ✅ |
| PVZ_SWITCH_FORM | 切换形态 | ✅ |
| IMAGE | image | — |
| AI_CLOUD | aiCloud | — |
| RANDOM | random | — |
| JS_EXPRESSION | jsExpression | — |
| VAR_EQUALS | varEquals | — |

## 二、条件类型（13 种）

| 枚举 | wire 名 |
|---|---|
| IMAGE | image |
| TEXT | textFount |
| COLOR | colorFound |
| AI_CLOUD | aiCloud |
| LIMIT_RUN_TIMES | limitRunTimes |
| SCRIPT_RUN_STATE | scriptRunState |
| RANDOM | random |
| TIME_AFTER | timeAfter |
| TIME_INTERVAL | timeInterval |
| JS_EXPRESSION | jsExpression |
| CONDITION_SET | conditionSet |
| AROUND | AroundCondition |
| VAR_EQUALS | varEquals |

## 三、通用字段（每个动作都有）

| 字段 | 含义 |
|---|---|
| `condition` | 条件（找图/找字/找色/变量比较/…） |
| `delay` + `delayUnit` | 动作前等待 |
| `duration` | 持续（点击按住时长 / 滑动时长） |
| `repeatCount` | 重复次数（0 = 无限） |
| `repeatDelayValue` + `repeatDelayUnit` | 重复间隔 |
| `jumpId` | 跳转标识（被 jumpTo / 候选 / 调用子链 引用） |
| `desc` | 备注 |
| `disabled` | 禁用 |
| `candidates` | 仅「等待候选」：候选列表（condition + jumpTo） |
| `waitTimeoutMs` / `waitIntervalMs` | 仅「等待候选」：超时 / 轮询间隔 |
| `scriptSet` | 仅「运行多个动作」：子动作 |

## 四、条件字段

| 字段 | 含义 |
|---|---|
| `runWhenFalse` | 取反 |
| `repeatWhenFalse` | 不成立时反复检查 |
| `repeatWhenFalseLimitTimes` | 0 = 无限，N = 最多 N 次 |
| `repeatWhenFalseRepeatDelay` | 每次间隔（ms） |
| `checkBeforeDelay` | 先判条件再等 delay（false 则相反） |
| `posData` | 识别位置（键名随类型变：text / colorData / image） |
| `varName` + `expected` | **仅 varEquals**：变量比较 |

## 五、游戏节点私有参数（extra，15 个）

| key | 含义 | 界面可改 |
|---|---|---|
| `pvzArtifact` | 神器种类 | ✅ |
| `pvzCameraMode` | 相机复制/粘贴 | ✅ |
| `pvzCol` | 列 | ✅ |
| `pvzDeck` | 编队号 1-6 | ✅ |
| `pvzGourdSize` | 葫芦体型 | ✅ |
| `pvzNeedColor` | ⚠️ 已废弃：用条件 colorFound 代替（仅兼容老脚本） | ❌ |
| `pvzPostMs` | 之后等待 ms（缺省 0） | ✅ |
| `pvzRow` | 行 | ✅ |
| `pvzSlot` | 卡槽 1-8 | ✅ |
| `pvzSupplyOrder` | 补给优先级 | ❌ |
| `pvzSwipeMs` | 拖动时长 ms（缺省 80） | ✅ |
| `pvzSwitchTimes` | 切换形态次数 1-3 | ✅ |
| `pvzWaitMs` | 识别等待 ms（缺省 20000） | ✅ |
| `pvzWaveInterval` | 点波间隔 ms | ✅ |
| `pvzWaveTimes` | 点波次数 | ✅ |

## 六、已知的重复/坑（别再犯）

- `pvzNeedColor` 与条件 `colorFound` **重复** → 新脚本一律用条件
- 拖动物/滑动时长既有通用 `duration` 又有 `pvzSwipeMs`；游戏节点以 `pvzSwipeMs` 为准
- `pvzWaitMs` 与「等待候选」的 `waitTimeoutMs` 含义相近但作用不同（前者是单次识别的等待上限）
