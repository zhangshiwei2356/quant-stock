# ZSW 品牌 Favicon / 顶栏 Logo 设计

日期：2026-08-08  
状态：待实现（设计已确认）

## 背景

备忘录目录提供三张 ZSW 方标（`zsw_黑色` / `zsw_透明底` / `zsw_深蓝`，均为 2048×2048 PNG）。工作台顶栏目前为文字方块「QS」+「Quant Stock」，浏览器标签页无项目品牌图标。本仓库为 Spring Boot + 静态 HTML（非 Thymeleaf），接入时用普通静态路径，不用 `th:*`。

## 目标

1. 将三张素材缩略后放入 `src/main/resources/static/` 合适位置。
2. 浏览器标签页显示 ZSW favicon。
3. 顶栏左侧用 Logo 图替换「QS」方块；**产品名文案仍为 Quant Stock**（副标题不变）。
4. 保留现有 `id="btnBrandHome"` 与回到欢迎页行为；不引入 React。

## 非目标

- 不把产品主标题全局改成「ZSW」。
- 不按主题自动切换黑/深蓝 Logo（备用文件仅入库）。
- 不改欢迎页大标题文案（除非另开需求）。
- 不引入 Thymeleaf / 新建导航栏结构。

## 素材映射（方案 A）

| 源文件 | 用途 | 目标路径 |
|--------|------|----------|
| `zsw_深蓝.png` | Favicon 源图 → 多尺寸 ico | `static/favicon.ico` |
| `zsw_透明底.png` | 顶栏 Logo | `static/images/logo.png` |
| `zsw_黑色.png` | 备用（如日后夜盘） | `static/images/logo-black.png` |
| `zsw_深蓝.png` | 备用缩略 | `static/images/logo-navy.png` |

处理约定：

- 原图约 2–3MB，入库前缩小并压缩：Logo 约 128×128 PNG；favicon 含 16×16、32×32（标准 ico，非改后缀）。
- 路径加版本查询参数（如 `?v=1`）减轻缓存旧图标问题。

## 页面与样式

### `stock.html`

- `<head>` 增加：
  - `<link rel="icon" href="/favicon.ico?v=1" type="image/x-icon">`
  - `<link rel="shortcut icon" href="/favicon.ico?v=1" type="image/x-icon">`
- 顶栏 brand 内：`<span class="brand-mark">QS</span>` →  
  `<img class="brand-logo" src="/images/logo.png?v=1" alt="ZSW" width="40" height="40">`
- 保留：`brand-title`「Quant Stock」、`brand-sub`「A股量化回测工作台」、`btnBrandHome`。

### `style.css`

- 新增 `.brand-logo`：高度约 40px、`width: auto`（或固定 40×40）、圆角与现有 `.brand-mark` 接近；可保留或弱化 `.brand-mark` 供兼容。

## 文档同步

按仓库约定，同一轮实现内更新：

- `README.md`：静态资源中品牌图标位置一句说明。
- `static/docs/app.html`（系统概述）：同上简短提及。

## 验收

1. 打开 `/stock.html`（或应用首页），标签页显示 ZSW 图标。
2. 顶栏左侧为 Logo 图 + Quant Stock 文案；点击仍回到欢迎页。
3. `/favicon.ico`、`/images/logo.png` 可直接访问；黑/深蓝备用文件存在。
4. Logo / favicon 单文件体积远小于原图（MB → KB 量级）。

## 自检（spec）

- 无 TBD / 占位符。
- 与已确认决策一致：映射 A、文案方案 1、缩小入库、静态路径。
- 范围仅品牌资源与顶栏/文档，不改交易逻辑。
