# ForkProbe × SkillHub 集成分析与功能报告

> **撰写日期**: 2026-08-11
> **分析对象**: [forkprobe](https://github.com/Jayden-X-L/forkprobe) (v0.8) × SkillHub (dev 分支)
> **核心命题**: 能否在 SkillHub 上安装 forkprobe 作为 skill？能否在用户下载 skill 时做 "skills 比较和评估"？

---

## 目录

1. [执行摘要](#1-执行摘要)
2. [ForkProbe 项目深度分析](#2-forkprobe-项目深度分析)
3. [SkillHub 现有能力盘点](#3-skillhub-现有能力盘点)
4. [集成方案设计](#4-集成方案设计)
5. [创意与创新点](#5-创意与创新点)
6. [技术实现路线图](#6-技术实现路线图)
7. [风险评估与缓解](#7-风险评估与缓解)
8. [结论与建议](#8-结论与建议)

---

## 1. 执行摘要

**核心结论: ForkProbe 可以且应该被安装为 SkillHub 的一个内置 skill，同时 forkprobe 的 "并行试跑 + 并排比较" 范式可以作为 SkillHub 的下一代 skill 评估基础设施。**

两层价值：

| 层次 | 方案 | 价值 |
|------|------|------|
| **L1: 作为 Skill** | forkprobe 打包为 SkillHub 内置 skill，用户下载后即可在自己的 Agent 中使用 | 让用户拥有"skill 选型能力"，直接可用 |
| **L2: 作为基础设施** | 将 forkprobe 的并行比较管线集成到 SkillHub 的下载/评估流程中 | 在下载前比较同类 skill 的真实输出，做出有数据支持的决策 |

现状：SkillHub 有版本间 diff 比较（`skill-version-compare`），但**没有跨 skill 的功能性比较**。用户选择 skill 只能看 README、评分和下载量——这些是 proxy 信号，不是真实输出。ForkProbe 填补的正是这个空白。

---

## 2. ForkProbe 项目深度分析

### 2.1 项目定位

ForkProbe 是一个 **"AI Skill 选型与试跑工具"**。核心理念：**别猜哪个 AI Skill 有用，直接并排看结果。**

```
你的任务 → [候选 skill A, 候选 skill B, baseline] → 并行试跑 → 本地 HTML report → AI 评审 → 你选 winner
```

### 2.2 技术架构

```
forkprobe/
├── SKILL.md              # Agent skill 指令 (30KB, 是 skill 本体)
├── scripts/              # 核心管线脚本
│   ├── recommend.py      # 候选推荐器 (82KB, 最复杂的模块)
│   ├── compare.py        # 文本对比管线 (23KB)
│   ├── figure_artifact.py    # 科研绘图对比 (38KB)
│   ├── research_artifact.py  # 调研报告对比 (42KB)
│   ├── web_artifact.py       # 网页成品对比 (51KB)
│   ├── video_artifact.py     # 视频成品对比 (52KB)
│   ├── discover_skills.py    # 多来源 skill 发现 (29KB)
│   ├── candidate_providers.py # 候选提供者抽象 (22KB)
│   ├── platform_adapter.py   # 多平台适配 (Codex/Claude/OpenAI)
│   ├── skill_loader.py       # Skill 加载器
│   ├── render_report.py      # 报告渲染
│   ├── render_artifact_report.py # 成品报告渲染
│   ├── verdict_server.py     # 本地裁决服务器
│   └── telemetry.py          # 匿名遥测
├── catalog/              # curated skill 目录 (JSON)
│   ├── academic-writing.json    # 学术写作 skill 目录
│   ├── pptx-artifact-skills.json # PPTX 生成 skill 目录
│   ├── web-artifact-skills.json  # 网页生成 skill 目录
│   └── video-artifact-skills.json # 视频生成 skill 目录
├── templates/
│   └── report.html.j2    # Jinja2 报告模板 (49KB)
├── services/
│   └── telemetry-worker/ # Cloudflare Worker + D1 匿名聚合
├── tests/
│   ├── test_smoke.py     # 冒烟测试 (110KB)
│   └── test_integration.py # 集成测试
└── docs/                 # GitHub Pages 发布页
```

### 2.3 核心能力矩阵

| 工作模式 | 产物类型 | 比较内容 | CLI 入口 |
|----------|---------|----------|----------|
| **Text Comparison** | 文本 | 多版本文本、AI 评审 | `compare.py` |
| **PPTX Artifact** | PPTX 文件 | 可打开的 PPTX、预览图、候选说明 | `render_artifact_report.py` |
| **Figure Artifact** | 科研图 | PNG/SVG/PDF/TIFF、源码、caption、QA | `figure_artifact.py` |
| **Research Report** | 调研报告 | 报告预览、sources.json、evidence table、limitations | `research_artifact.py` |
| **Web Artifact** | 可运行网页 | 桌面/移动端截图、QA、源码、AI 评审 | `web_artifact.py` |
| **Video Artifact** | 视频成片 | MP4 播放、封面、字幕、脚本、媒体 QA | `video_artifact.py` |

### 2.4 候选发现机制（多来源融合）

这是 forkprobe 最精巧的设计——候选人（candidate skills）来自 5 个来源，按内容指纹去重：

| 来源 | 说明 |
|------|------|
| **Curated Catalog** | `catalog/*.json` 手工策展的高质量 skill 目录 |
| **Local Skills** | 自动扫描 `~/.claude/skills`、`~/.codex/skills`、`~/.agents/skills` 等目录 |
| **EverMind Skill Hub** | 调用 EverMind 开放 API 搜索 |
| **GitHub Search** | 基于清洗后的场景词搜索 GitHub |
| **BYO (Bring Your Own)** | 本地路径、GitHub URL、`repo#subdir`、raw `SKILL.md` URL |

推荐器（`recommend.py`）会：
1. 提取任务信号（场景词，非原始内容）
2. 从 5 个来源拉取候选
3. 按内容指纹去重
4. 按场景匹配度排序
5. **等待用户确认**后才执行（人在闭环）

### 2.5 设计原则

1. **Local-first**: 报告在本地生成，不上传任务内容
2. **Real output comparison**: 比较真实产物，非 README/star 数/截图
3. **AI Judge + Human Decision**: AI 给建议，人做最终选择
4. **Isolated Workspaces**: 每个候选在独立工作区运行，防止交叉污染
5. **Privacy by Design**: 任务内容、候选输出、本地路径始终留在本地

### 2.6 当前局限

- **单机运行**: 没有服务端组件，无法作为共享基础设施
- **Python 脚本为主**: 没有 API 化，集成需要包装
- **依赖外部 Agent**: 实际执行靠 Codex/Claude/OpenAI，不自带模型
- **Catalog 静态**: 策展目录手工维护，没有自动更新机制
- **无持久化对比历史**: 每次运行独立，历史 report 之间无法追溯

---

## 3. SkillHub 现有能力盘点

### 3.1 与 forkprobe 相关的现有功能

| SkillHub 功能 | 覆盖度 | 与 forkprobe 的关系 |
|---------------|--------|---------------------|
| **Skill 发布/下载** | ✅ 完整 | forkprobe 可直接作为 skill 发布 |
| **版本比较** (`skill-version-compare`) | ⚠️ 同 skill 不同版本 diff | 互补——forkprobe 做跨 skill 比较 |
| **安全扫描** (cisco-ai-skill-scanner) | ✅ 安全检查 | 互补——forkprobe 做功能性比较 |
| **评审系统** (ReviewTask) | ✅ 人工评审 | 互补——forkprobe 做自动化试跑评审 |
| **社交指标** (star/rating/download) | ✅ 量化指标 | 竞品——都是选择参考，但 forkprobe 看真实输出 |
| **搜索** (full-text + semantic) | ✅ 搜索引擎 | forkprobe 的候选推荐可增强搜索 |
| **Label/Tag 系统** | ✅ 分类体系 | 可用来给 forkprobe 的 catalog 提供数据 |
| **Builtin Skills** | ✅ 15 个内置 skill | forkprobe 作为第 16 个内置 skill |
| **Sync-out** (离线分发) | ✅ 打包分发 | forkprobe 可打入离线 bundle |

### 3.2 关键缺口

SkillHub **缺失**的能力（恰好是 forkprobe 的核心）：

```
[用户选择 skill]
    现有路径: README → 评分/下载量 → 安全扫描结果 → 安装
    缺失环节: 真实输出对比 → 在你的任务上的表现 → 有数据的决策
                ↑
           forkprobe 填补这个
```

具体而言，SkillHub 没有：
1. **跨 skill 功能比较**: `skill-version-compare` 只比较同一 skill 的不同版本
2. **试跑沙箱**: 没有安全隔离环境让 skill 在真实任务上运行
3. **输出驱动的评估**: 所有评估信号都是 proxy（元数据、社交指标），不是真实输出
4. **自动化 A/B**: 没有机制同时跑多个 skill 并对比结果

---

## 4. 集成方案设计

### 4.1 方案总览

```
┌─────────────────────────────────────────────────────────────┐
│                      SkillHub                               │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ Skill 市场   │  │ 下载流程      │  │ 管理后台           │  │
│  │             │  │              │  │                   │  │
│  │ [比较按钮]  │  │ [下载前比较]  │  │ [Benchmark 管理]  │  │
│  └──────┬──────┘  └──────┬───────┘  └────────┬──────────┘  │
│         │                │                    │             │
│         └────────────────┼────────────────────┘             │
│                          ▼                                   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │           ForkProbe Integration Layer                 │   │
│  │  ┌────────────┐ ┌──────────┐ ┌───────────────────┐  │   │
│  │  │ Candidate  │ │ Sandbox  │ │ Report Generator  │  │   │
│  │  │ Discovery  │ │ Runner   │ │ (HTML/Template)   │  │   │
│  │  └────────────┘ └──────────┘ └───────────────────┘  │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 方案 L1: ForkProbe 作为 SkillHub 内置 Skill（立即可做）

**做法**: 将 forkprobe 仓库打包，加入 SkillHub 的 `builtin-skills` 并注册到 manifest。

**实现步骤**:
1. 将 forkprobe 的 `SKILL.md` + 核心脚本打包为符合 OpenSkills 协议的 zip
2. 在 `builtin-skills/manifest.json` 中注册
3. 作为 `global/forkprobe` 发布到 SkillHub
4. 用户通过 `skillhub install global/forkprobe` 安装

**收益**:
- 零后端改动，纯内容操作
- 用户安装后可直接在自己的 Agent 中使用 forkprobe 做 skill 选型
- 证明了 OpenSkills 协议的互操作性

**局限**: 这是让用户在本地跑 forkprobe，没有利用 SkillHub 的服务端能力。

### 4.3 方案 L2: "下载前比较" —— 集成到 SkillHub 下载流程（核心方案）

**触发时机**: 用户点击"下载/安装"某个 skill 时，如果系统中存在同类 skill，弹出比较选项。

**UX 流程**:
```
用户在 SkillHub Web 查看 skill A 的详情页
  → 点击"安装"
  → 系统检测到同类 skill: B, C, D
  → 弹出对话框:
     ┌─────────────────────────────────────────┐
     │  📊 同类 Skill 快速比较                   │
     │                                         │
     │  当前任务: "帮我写一篇学术论文的摘要"      │
     │                                         │
     │  候选 Skill        │  质量评分  │ 匹配度  │
     │  ──────────────────┼──────────┼────────│
     │  nature-polishing  │  ⭐ 4.8  │  92%   │ ← 推荐
     │  paper-writer      │  ⭐ 4.5  │  87%   │
     │  academic-humanizer│  ⭐ 4.2  │  85%   │
     │  baseline (无skill) │  ──     │  ──    │
     │                                         │
     │  [查看详细对比 Report]  [直接安装 skill A] │
     └─────────────────────────────────────────┘
```

**后端改动**:
1. **Candidate Discovery Service** (新增): 基于 skill 的 label/tag/描述，发现同类 skill
2. **Comparative Metrics** (新增 `skill_comparison` 表): 存储 skill 间的比较数据
3. **Report API** (新增): `GET /api/portal/skills/{ns}/{slug}/compare-with/{other}`

**前端改动**:
1. **Skill 详情页**: 增加"同类比较"区块
2. **下载对话框**: 增加比较提示
3. **比较 Report 页**: 新页面展示并排比较（可复用 forkprobe 的 Jinja2 模板）

### 4.4 方案 L3: SkillHub Benchmark 基础设施（长期愿景）

这是把 forkprobe 的核心理念变成 SkillHub 的**平台级能力**。

**架构**:

```
┌──────────────────────────────────────────────────────────┐
│               SkillHub Benchmark Service                  │
│                                                          │
│  ┌─────────────────┐  ┌───────────────────────────┐     │
│  │ Benchmark        │  │ Sandbox Runner (Docker)    │     │
│  │ Definitions      │  │                           │     │
│  │ (任务模板库)     │  │ ┌───────┐ ┌───────┐      │     │
│  │                 │  │ │Skill A│ │Skill B│ ...  │     │
│  │ - 学术润色       │  │ │Container│Container│    │     │
│  │ - PPTX 生成      │  │ └───┬───┘ └───┬───┘      │     │
│  │ - 科研绘图       │  │     │         │          │     │
│  │ - 网页制作       │  │  ┌──┴─────────┴──┐      │     │
│  │ - 视频制作       │  │  │  Output Coll.  │      │     │
│  │ - ...           │  │  └───────┬────────┘      │     │
│  └─────────────────┘  │          │                │     │
│                       │  ┌───────┴────────┐      │     │
│                       │  │  AI Judge      │      │     │
│                       │  └───────┬────────┘      │     │
│                       │          │                │     │
│                       │  ┌───────┴────────┐      │     │
│                       │  │  Report Gen    │      │     │
│                       │  └────────────────┘      │     │
│                       └───────────────────────────┘     │
└──────────────────────────────────────────────────────────┘
```

**核心组件**:

| 组件 | 说明 | 技术选型 |
|------|------|----------|
| **Benchmark Task Store** | 标准化测试任务模板库 | PostgreSQL (复用现有) |
| **Sandbox Runner** | Docker 隔离环境，安全运行 skill | Docker API / K8s Jobs |
| **Output Collector** | 收集每个候选 skill 的输出产物 | Object Storage (复用 S3/MinIO) |
| **AI Judge** | 多维度评分（质量、速度、资源消耗） | Claude API / 可插拔 |
| **Report Generator** | 生成并排比较 HTML report | 复用 forkprobe Jinja2 模板 |
| **Metrics Aggregator** | 汇总社区比较数据，生成胜率 | 类似 forkprobe telemetry-worker |

**Benchmark Task 示例**:
```json
{
  "id": "bench-academic-polishing-001",
  "category": "academic-writing",
  "task_description": "Polish this paragraph to Nature journal standards while preserving scientific accuracy.",
  "input_file": "samples/abstract_draft.md",
  "evaluation_criteria": [
    "scientific_accuracy",
    "language_quality",
    "conciseness",
    "journal_style_compliance"
  ],
  "expected_output_type": "text/markdown"
}
```

### 4.5 方案对比

| 维度 | L1: 作为 Skill | L2: 下载前比较 | L3: Benchmark 平台 |
|------|---------------|---------------|-------------------|
| **实施复杂度** | 极低 | 中 | 高 |
| **后端改动** | 零 | 新增 2-3 个 API + 1 个 Service | 新增完整子系统 |
| **前端改动** | 零 | 新增 1 个组件 + 1 个页面 | 新增模块 |
| **用户价值** | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **与 SkillHub 的耦合度** | 松耦合 | 中等耦合 | 紧耦合 |
| **可立即开始** | ✅ 是 | ✅ 是 (先做推荐部分) | ⚠️ 需要规划设计 |
| **差异化竞争力** | 低 | 中 | 高 |

---

## 5. 创意与创新点

### 5.1 "Skill Battle" —— 下载前的 A/B 试跑

**概念**: 在 SkillHub 上，当用户要下载一个 skill 时，系统自动找到 2-3 个同类 skill，在一个共享 sandbox 中运行同一个微任务，生成对比报告。

**差异化**: 目前没有任何 skill registry 做这件事。Anthropic 的 skills repo、Codex 的 skill store、EverMind Hub——都是 metadata 驱动的选择。ForkProbe + SkillHub 的组合会是第一个**输出驱动的选择平台**。

### 5.2 "Community Benchmark Score" (CBS)

**概念**: 利用 forkprobe v0.8 的匿名 Winner 分享机制，在 SkillHub 层面聚合所有用户的试跑结果，给每个 skill 一个基于真实输出的 CBS 分数。

**区别于现有评分**: 现有的 star/rating 是主观的，CBS 是客观的——"在 N 次学术润色任务中，这个 skill 被选为 winner 的比例是 68%"。

**隐私保护**: forkprobe 的 telemetry 设计已经考虑到了这一点——只上传 `task_type`、`candidate_skill_names`、`final_choice`，不上传任务内容。

### 5.3 "Skill Genome" —— 能力指纹图谱

**概念**: 对每个 skill 自动生成"能力指纹"——在标准化 benchmark 任务矩阵上的表现向量。

```
nature-polishing:
  学术润色:    ████████░░ 8.2
  中译英:      ████████░░ 8.5
  审稿回复:    ██████░░░░ 6.8
  去AI味:      ████░░░░░░ 4.2
  速度(token): ██████░░░░ 中等
```

**可视化**: 用户可以在 SkillHub 上打开雷达图，直观看到 skill 的能力分布。两个 skill 的雷达图叠加就是最好的比较。

### 5.4 "Try Before Install" —— 临时沙箱试跑

**概念**: 在 SkillHub Web UI 中直接提供"试跑"按钮。用户粘贴一小段任务描述，系统在后台 sandbox 中跑 2-3 个推荐 skill，30 秒后返回对比结果。

**技术可行性**: SkillHub 已有 scanner Docker 服务——sandbox runner 可以复用这个模式。

### 5.5 "Cross-Platform Skill Compatibility Score"

**概念**: forkprobe 支持 15+ Agent 平台（Claude Code, Codex, Cursor, etc.）。SkillHub 可以展示每个 skill 在目标平台上的兼容性评分——这个数据可以来自 forkprobe 的多平台适配层。

### 5.6 "Skill Chain Builder" —— 从比较到编排

**概念**: forkprobe 的比较不只是选 winner——它验证了哪些 skill 可以串联。如果 skill A 擅长生成大纲、skill B 擅长润色，用户可以在比较后选择"用 A 生成大纲，再用 B 润色"。

这打开了 **"skill pipeline 组合"** 的市场——比单个 skill 更高维度的产品。

---

## 6. 技术实现路线图

### Phase 0: Quick Win (第 1-2 周)

**目标**: ForkProbe 作为内置 skill 上线 SkillHub。

```
任务列表:
□ 将 forkprobe 打包为 OpenSkills 兼容的 zip 包
□ 在 builtin-skills/manifest.json 中注册
□ 上传到 CDN 并更新 manifest SHA-256
□ 在 Web UI 的 skill 详情页展示安装命令
□ CLI: skillhub install global/forkprobe 可正常安装
```

### Phase 1: 下载前推荐 (第 3-6 周)

**目标**: 用户下载 skill 时看到同类比较建议。

```
后端任务:
□ 新增 SkillComparisonService (domain 层)
  - findSimilarSkills(skillId, limit=5): Skill[]
  - 基于 label 交集 + 描述语义相似度
□ 新增 API: GET /api/portal/skills/{ns}/{slug}/similar
□ 新增 comparative_metrics 表:
  - skill_id_a, skill_id_b, task_category, comparison_count
  - a_wins, b_wins, tie_count
  - last_compared_at

前端任务:
□ 在 Skill 详情页增加 "Similar Skills" 区块
□ 在下载按钮旁增加 "Compare Before Download" 触发
□ 实现 CompareDialog 组件（列出同类 skill + 基础对比信息）
```

### Phase 2: Sandbox Compare MVP (第 7-12 周)

**目标**: 用户可以在 SkillHub 上发起一次真实的并排试跑。

```
后端任务:
□ Sandbox Runner (复用 Docker, 类似 scanner 的模式)
  - 为每个候选 skill 创建临时容器
  - 注入标准测试任务
  - 收集输出产物到 Object Storage
□ AI Judge Service
  - 调用 Claude API 做多维度评审
  - 返回评分 + 推荐理由
□ Report Generator
  - 复用 forkprobe 的 Jinja2 模板
  - 生成 HTML report 存储到 Object Storage
□ 新增 API:
  - POST /api/portal/skills/compare (发起比较任务)
  - GET /api/portal/skills/compare/{taskId} (查询比较结果)

前端任务:
□ CompareRunner 组件（输入任务描述 → 显示进度 → 展示 report）
□ CompareReport 页面（iframe 嵌入生成的 HTML report）
```

### Phase 3: Benchmark 平台 (第 13-20 周)

**目标**: 完整的 skill benchmark 基础设施 + CBS 评分。

```
后端任务:
□ Benchmark Task Store + 管理后台
□ Scheduled/Triggered benchmark runs
□ CBS 计算 + 聚合 pipeline
□ Community telemetry 接收端 (复用 forkprobe telemetry-worker)
□ 雷达图数据 API

前端任务:
□ Skill 详情页集成雷达图
□ Benchmark 结果浏览页
□ "Try Before Install" 交互
```

---

## 7. 风险评估与缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| **Sandbox 安全** | 高 | 中 | Docker 严格隔离 + 网络限制 + 资源限额 + 超时 kill |
| **LLM API 成本** | 中 | 高 | Judge 用小模型 (Haiku) + 缓存 + 按需触发 |
| **执行时间过长** | 中 | 高 | 异步任务 + 进度通知 + 超时控制 (参考 forkprobe 的时间估算) |
| **Skill 不兼容** | 低 | 中 | 平台适配层 (复用 forkprobe platform_adapter.py) |
| **隐私泄露** | 高 | 低 | 测试任务用脱敏模板 + Sandbox 网络隔离 + 任务内容不落盘 |
| **与 forkprobe 上游不同步** | 中 | 中 | 定期 sync upstream + catalog 数据可独立维护 |

---

## 8. 结论与建议

### 8.1 直接回答用户的问题

**Q: 能否在 SkillHub 上安装 forkprobe？**

✅ **能，而且应该。** ForkProbe 本身就是符合 SKILL.md 协议的 skill，可以直接作为内置 skill 安装。这是 Phase 0，工作量极低，立即可做。

**Q: 当用户下载时做 skills 比较和评估？**

✅ **可以，而且这是 SkillHub 的下一代差异化能力。** 核心思路是把 forkprobe 的 "并行试跑 + 并排比较" 范式集成到 SkillHub 的下载流程中，让用户在选择 skill 时看到真实输出而非 proxy 信号。

### 8.2 推荐执行路径

```
Phase 0 (立即): L1 — forkprobe 作为内置 skill 上架
    ↓
Phase 1 (1 个月): L2 — "下载前比较推荐" MVP
    ↓
Phase 2 (2 个月): L2+ — Sandbox Compare 可用
    ↓
Phase 3 (3 个月): L3 — Benchmark 平台上线
```

### 8.3 一句话总结

> ForkProbe 是 **skill 的 skill**——它不解决最终用户任务，而是解决"选哪个 skill"这个元问题。SkillHub 作为 skill 的 marketplace，天然需要这个元能力。二者的结合不是 1+1，而是让 SkillHub 从一个 **skill 目录**进化成一个 **skill 决策平台**。

---

## 附录

### A. ForkProbe 与 SkillHub 的架构互补性

| ForkProbe 有 / SkillHub 缺 | SkillHub 有 / ForkProbe 缺 |
|---------------------------|---------------------------|
| 跨 skill 并行试跑 | 持久化 skill 注册与发现 |
| AI Judge + 对比 Report | 用户认证与权限体系 |
| 多来源候选发现 | 评审与治理流程 |
| 多 artifact 类型支持 | 安全扫描 |
| 匿名社区统计 | Web UI + CLI + API |
| 本地隐私保护 | 企业级部署 (K8s/Helm) |

### B. 相关资源

- ForkProbe GitHub: https://github.com/Jayden-X-L/forkprobe
- ForkProbe 发布页: https://jayden-x-l.github.io/forkprobe/
- SkillHub 项目: `/home/sw/app/skillhub`
- SkillHub Skill 协议: `/home/sw/app/skillhub/docs/07-skill-protocol.md`
- SkillHub 版本比较: `/home/sw/app/skillhub/web/src/pages/skill-version-compare.tsx`
