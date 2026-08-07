# ZSW Favicon / 顶栏 Logo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将备忘录三张 ZSW 方标缩略入库，并接入浏览器 favicon 与工作台顶栏 Logo（文案仍为 Quant Stock）。

**Architecture:** 源图在 `D:\备忘录\`；用 Python Pillow 生成 `static/favicon.ico` 与 `static/images/logo*.png`；`stock.html` 用静态绝对路径引用；CSS 用 `.brand-logo` 替换 `.brand-mark` 视觉位。

**Tech Stack:** Spring Boot 静态资源、`stock.html`、`style.css`、Pillow（本机已装）

## Global Constraints

- 映射：深蓝 → favicon；透明底 → `logo.png`；黑/深蓝缩略备用 `logo-black.png` / `logo-navy.png`
- 文案：主标题仍为 Quant Stock，副标题不变；保留 `id="btnBrandHome"`
- 非 Thymeleaf：`href="/favicon.ico?v=1"`、`src="/images/logo.png?v=1"`
- 不引入 React；不按主题自动切 Logo
- 同步 README + `docs/app.html`；未要求则不 git commit
- 原图不直接入库（须缩小）

## File map

| 路径 | 职责 |
|------|------|
| `static/favicon.ico` | 标签页图标（新建） |
| `static/images/logo.png` | 顶栏 Logo（新建） |
| `static/images/logo-black.png` | 黑色备用（新建） |
| `static/images/logo-navy.png` | 深蓝备用（新建） |
| `static/stock.html` | link icon + brand img |
| `static/css/style.css` | `.brand-logo` 样式 |
| `README.md` / `static/docs/app.html` | 一句说明 |

---

### Task 1: 生成并落盘静态图片

**Files:**
- Create: `src/main/resources/static/favicon.ico`
- Create: `src/main/resources/static/images/logo.png`
- Create: `src/main/resources/static/images/logo-black.png`
- Create: `src/main/resources/static/images/logo-navy.png`

**Interfaces:**
- Consumes: `D:\备忘录\zsw_深蓝.png`、`zsw_透明底.png`、`zsw_黑色.png`
- Produces: 上述四个静态文件（Logo 128×128 PNG；ico 含 16+32）

- [ ] **Step 1: 确保 images 目录存在并运行 Pillow 脚本**

在仓库根目录执行：

```powershell
New-Item -ItemType Directory -Force -Path "src\main\resources\static\images" | Out-Null
python -c @"
from PIL import Image
from pathlib import Path

memo = Path(r'D:\备忘录')
static = Path(r'src/main/resources/static')
images = static / 'images'
images.mkdir(parents=True, exist_ok=True)

def load(name):
    return Image.open(memo / name).convert('RGBA')

# logos 128x128
pairs = [
    ('zsw_透明底.png', 'logo.png'),
    ('zsw_黑色.png', 'logo-black.png'),
    ('zsw_深蓝.png', 'logo-navy.png'),
]
for src, dst in pairs:
    img = load(src)
    img.resize((128, 128), Image.Resampling.LANCZOS).save(images / dst, optimize=True)
    print('wrote', dst, (images / dst).stat().st_size)

# favicon from navy
navy = load('zsw_深蓝.png')
sizes = [(16, 16), (32, 32)]
icons = [navy.resize(s, Image.Resampling.LANCZOS) for s in sizes]
icons[0].save(static / 'favicon.ico', format='ICO', sizes=sizes, append_images=icons[1:])
print('wrote favicon.ico', (static / 'favicon.ico').stat().st_size)
"@
```

Expected: 四个文件写出；各 PNG ≪ 原图；`favicon.ico` 存在。

- [ ] **Step 2: 校验尺寸与体积**

```powershell
python -c "from PIL import Image; from pathlib import Path; p=Path('src/main/resources/static');
for f in ['images/logo.png','images/logo-black.png','images/logo-navy.png','favicon.ico']:
  im=Image.open(p/f); print(f, im.size, (p/f).stat().st_size)"
```

Expected: logo* 为 `(128, 128)`；体积为 KB 级（非 MB）。

---

### Task 2: 接入 stock.html + CSS

**Files:**
- Modify: `src/main/resources/static/stock.html`（`<head>` 与顶栏 brand）
- Modify: `src/main/resources/static/css/style.css`（`.brand-logo`；`.brand-mark` 可保留）

**Interfaces:**
- Consumes: `/favicon.ico?v=1`、`/images/logo.png?v=1`
- Produces: 标签页图标 + 顶栏 Logo 可见；`btnBrandHome` 行为不变

- [ ] **Step 1: 在 stock.html head 中 echarts 脚本前加入 favicon**

在 `<title>` 后增加：

```html
  <link rel="icon" href="/favicon.ico?v=1" type="image/x-icon"/>
  <link rel="shortcut icon" href="/favicon.ico?v=1" type="image/x-icon"/>
```

- [ ] **Step 2: 替换 brand-mark 为 img**

将：

```html
    <span class="brand-mark">QS</span>
```

改为：

```html
    <img class="brand-logo" src="/images/logo.png?v=1" alt="ZSW" width="40" height="40"/>
```

保留 `btnBrandHome`、`brand-title`、`brand-sub` 不变。

- [ ] **Step 3: 增加 .brand-logo 样式**

在 `.brand-mark` 块附近增加：

```css
.brand-logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  object-fit: cover;
  display: block;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.25);
  flex-shrink: 0;
}
```

可选：bump `style.css` 的 `?v=` 查询参数以便强刷样式。

- [ ] **Step 4: 静态文件可访问性抽检**

若应用已启动，浏览器或 curl：`/favicon.ico`、`/images/logo.png` 返回 200。未启动时确认文件在 `src/main/resources/static/` 即可。

---

### Task 3: 同步 README 与系统概述

**Files:**
- Modify: `README.md`（静态资源 / 前端一句）
- Modify: `src/main/resources/static/docs/app.html`（系统概述一句）

- [ ] **Step 1: README**

在「非前后端分离 / static」相关段落后补一句，例如：

> 浏览器标签页图标为 `static/favicon.ico`（ZSW）；工作台顶栏 Logo 为 `static/images/logo.png`（透明底；另存 `logo-black.png` / `logo-navy.png` 备用）。

- [ ] **Step 2: app.html**

在功能模块或启动相关列表中补一句：

> 顶栏与标签页使用 ZSW 品牌图标（`/favicon.ico`、`/images/logo.png`）。

- [ ] **Step 3: 清理临时目录（若有）**

删除仓库内 `.tmp-brand/`（若存在，勿提交）。

- [ ] **Step 4: Commit（仅当用户明确要求）**

中文备注示例：`feat: 接入 ZSW favicon 与顶栏 Logo`

---

## Spec coverage self-check

| Spec 项 | Task |
|---------|------|
| 三图入库+缩小 | Task 1 |
| favicon 深蓝 ico | Task 1 |
| 顶栏透明底 logo + Quant Stock 文案 | Task 2 |
| 黑/深蓝备用 | Task 1 |
| README + app.html | Task 3 |
| 不改欢迎页大标题 / 不主题切换 | 未列入任务（刻意不做） |
