# 设计：默认主题「浪花」（cosmos / mode=surf）

**日期：** 2026-08-07 → 2026-08-08  
**状态：** 已落地  
**范围：** 默认主题背景。`data-theme="cosmos"`；Canvas `mode: 'surf'`。不改夜盘/银河交易逻辑。

## 现行能力

- 显示名：**浪花**
- 绘制：`drawDaySurfScene` → 天幕 + glow + `drawDaySurfWaves`（左低右高）+ veil
- **无**粒子网、光帘、网格/扫描

## 已删除的冗余（2026-08-08）

- `drawAurora` / `drawDayAuroraRibbons` / `drawTechHud`
- `drawWaves` / `drawAmbient`（无现行主题再走）
- cosmos 上已关却残留的 aurora / particle 配置项
- `auroraDay` 标志（改由 `mode: 'surf'` 路由）

## 保留

| key | 用途 |
|---|---|
| `cosmos` | 浪花（surf） |
| `night` / `matrix` | 代码雨 |
| `forest` / `wave` | Canvas off，银河由 starfield |

## 验收

1. 「浪花」仅浪面+飞沫+天幕，无粒子/光帘/网格  
2. 夜盘、银河切换正常  
3. 旧 `aurora` localStorage 仍映射 cosmos  
