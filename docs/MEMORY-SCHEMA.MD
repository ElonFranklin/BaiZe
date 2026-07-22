# MEMORY-SCHEMA.MD — 白泽记忆系统数据结构 v1.0

> 设计：雪瑶 | 审核：兰兰 | 日期：2026-05-27
> 
> 本 schema 为白泽 APP + 无名角色设计，与灵魂引擎的 Tier 系统和望闻问切框架紧密耦合。
> 其他项目可参考，但需要根据自身架构调整。

---

## 一、核心概念

| 概念 | 定义 | 类比 |
|------|------|------|
| **记忆条目 (MemoryEntry)** | 从对话中提取的、值得记住的单一信息单元 | 神经元 |
| **模式 (Pattern)** | 从多个记忆条目中归纳出的重复出现的行为/情绪/话题 | 神经通路 |
| **洞察 (Insight)** | 跨时间段对比模式后生成的、关于"变化"的观察 | 顿悟 |
| **承诺 (Promise)** | 用户明确或隐含的、需要后续跟进的事项 | 待办 |
| **快照 (Snapshot)** | 用户在某个时间点的状态摘要 | 时间戳照片 |
| **会话 (ConversationSession)** | 一次完整对话的元数据 | 旅程记录 |

---

## 二、表结构

### 2.1 MemoryEntry（记忆条目）

最基础的存储单元。每条对话可能产生 0~N 个记忆条目。

```sql
CREATE TABLE memory_entry (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    persona         TEXT NOT NULL DEFAULT '默认',      -- 人格隔离（与现有v3兼容）
    user_id         TEXT NOT NULL DEFAULT 'local',     -- 用户标识（本地模式用 'local'）
    session_id      TEXT,                              -- 来源会话ID
    content         TEXT NOT NULL,                     -- 记忆内容（自然语言）
    content_short   TEXT,                              -- 简短摘要（≤50字，用于快速检索）

    -- 分类
    type            TEXT NOT NULL DEFAULT 'event',     -- 事件/情绪/决定/模式/偏好/观察/承诺/背景
    subtype         TEXT,                              -- 细分类型（见枚举表）

    -- 语义标签
    topics          TEXT,                              -- JSON数组：话题标签 ["工作","家庭"]
    emotion         TEXT,                              -- 主导情绪标签
    emotion_intensity REAL,                            -- 情绪强度 0-10

    -- 重要性与衰减
    importance      REAL NOT NULL DEFAULT 5,           -- 重要性 0-10（10=极其重要）
    decay_rate      REAL NOT NULL DEFAULT 0.9,         -- 衰减速率（0.9=每30天衰减10%，1.0=不衰减）

    -- 时间
    created_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    last_accessed   TEXT,                              -- 最后一次被检索/引用的时间
    access_count    INTEGER NOT NULL DEFAULT 0,        -- 被检索次数

    -- 关联
    parent_id       INTEGER REFERENCES memory_entry(id), -- 来源/关联的记忆（来源链）
    pattern_id      INTEGER REFERENCES pattern(id),      -- 归属的模式（后填）

    -- 元数据
    source_snippet  TEXT,                              -- 原始对话片段（用于回溯验证）
    confidence      REAL NOT NULL DEFAULT 0.8,         -- 提取置信度 0-1
    embedding       BLOB,                              -- 预留：向量嵌入，后续可扩展为向量检索

    -- 软删除
    is_deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_memory_persona_type ON memory_entry(persona, type);
CREATE INDEX idx_memory_persona_time ON memory_entry(persona, created_at DESC);
CREATE INDEX idx_memory_persona_importance ON memory_entry(persona, importance DESC);
CREATE INDEX idx_memory_user_time ON memory_entry(user_id, created_at DESC);
CREATE INDEX idx_memory_pattern ON memory_entry(pattern_id) WHERE pattern_id IS NOT NULL;
CREATE INDEX idx_memory_session ON memory_entry(session_id);
CREATE INDEX idx_memory_type ON memory_entry(type) WHERE is_deleted = 0;
```

#### type 枚举

| type | 含义 | 示例 |
|------|------|------|
| `event` | 用户经历的具体事件 | "今天面试被拒了" |
| `emotion` | 用户表达的情绪状态 | "最近很焦虑" |
| `decision` | 用户做出或正在考虑的决定 | "我在想要不要换工作" |
| `pattern` | 用户自身反复出现的行为/倾向 | "每次谈到家庭就转移话题" |
| `preference` | 用户的偏好/喜好 | "不喜欢被直接给答案" |
| `observation` | 无名对用户的观察（非用户原话） | "用户最近提到工作的频率上升了" |
| `promise` | 用户的承诺/计划（详细跟踪在 Promise 表） | "我明天开始每天跑步" |
| `context` | 背景信息 | "用户是程序员，35岁" |

#### subtype 枚举

| type | subtype |
|------|---------|
| event | achievement / setback / milestone / daily |
| emotion | anxiety / sadness / joy / frustration / confusion |
| decision | career / relationship / health / finance / life |
| pattern | avoidance / rumination / growth / coping / self否定 |
| preference | communication / learning / feedback |

---

### 2.2 Pattern（模式）

从多个 MemoryEntry 中归纳出的重复出现的规律。

```sql
CREATE TABLE pattern (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    persona         TEXT NOT NULL DEFAULT '默认',
    user_id         TEXT NOT NULL DEFAULT 'local',
    pattern_type    TEXT NOT NULL,                     -- 模式类型
    description     TEXT NOT NULL,                     -- 自然语言描述

    -- 时间跨度
    first_seen      TEXT NOT NULL,                     -- 最早出现时间
    last_seen       TEXT NOT NULL,                     -- 最近出现时间
    observation_count INTEGER NOT NULL DEFAULT 1,      -- 观察到的次数

    -- 状态
    status          TEXT NOT NULL DEFAULT 'active',    -- active / resolved / superseded
    confidence      REAL NOT NULL DEFAULT 0.6,         -- 模式置信度 0-1
    importance      REAL NOT NULL DEFAULT 5,           -- 重要性 0-10

    -- 关联
    evidence_refs   TEXT,                              -- JSON数组：支撑此模式的 memory_entry IDs

    -- 元数据
    created_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),

    is_deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_pattern_persona_type ON pattern(persona, pattern_type);
CREATE INDEX idx_pattern_persona_status ON pattern(persona, status);
CREATE INDEX idx_pattern_persona_importance ON pattern(persona, importance DESC);
```

#### pattern_type 枚举

| pattern_type | 含义 | 示例 |
|-------------|------|------|
| `emotional` | 情绪模式 | "每逢周一情绪低落" |
| `behavioral` | 行为模式 | "遇到冲突时倾向于回避" |
| `cognitive` | 思维模式 | "习惯性自我否定" |
| `relational` | 关系模式 | "对权威人物有防御心理" |
| `growth` | 成长模式 | "面对困难的韧性在增强" |
| `temporal` | 时间模式 | "深夜容易焦虑" |
| `topic` | 话题模式 | "近一个月频繁提到工作压力" |

---

### 2.3 Insight（跨时间洞察）

对比不同时间段的模式后生成的、关于"变化"的观察。无名"时间厚度"能力的核心。

```sql
CREATE TABLE insight (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    persona         TEXT NOT NULL DEFAULT '默认',
    user_id         TEXT NOT NULL DEFAULT 'local',
    insight_text    TEXT NOT NULL,                     -- 洞察内容（自然语言）

    -- 时间对比
    time_a_start    TEXT,                              -- 第一个时间段起点
    time_a_end      TEXT,                              -- 第一个时间段终点
    time_b_start    TEXT,                              -- 第二个时间段起点
    time_b_end      TEXT,                              -- 第二个时间段终点

    -- 关联
    pattern_a_id    INTEGER REFERENCES pattern(id),   -- 时间段A的模式
    pattern_b_id    INTEGER REFERENCES pattern(id),   -- 时间段B的模式
    related_entries TEXT,                              -- JSON数组：相关的 memory_entry IDs

    -- 交付
    generated_at    TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    delivered       INTEGER NOT NULL DEFAULT 0,        -- 是否已向用户表达
    delivered_at    TEXT,                              -- 表达时间
    user_reaction   TEXT,                              -- 用户对这个洞察的反应

    -- 质量
    confidence      REAL NOT NULL DEFAULT 0.6,         -- 洞察置信度 0-1
    insight_type    TEXT NOT NULL DEFAULT 'growth',    -- growth / regression / shift / contrast

    is_deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_insight_persona_type ON insight(persona, insight_type);
CREATE INDEX idx_insight_persona_time ON insight(persona, generated_at DESC);
CREATE INDEX idx_insight_undelivered ON insight(persona, delivered) WHERE delivered = 0;
```

#### insight_type 枚举

| insight_type | 含义 | 示例 |
|-------------|------|------|
| `growth` | 正向变化 | "三个月前你说害怕不确定，现在你的选择越来越大胆了" |
| `regression` | 退步 | "你之前已经能平静谈论这件事了，最近好像又退回来了" |
| `shift` | 方向转变 | "你以前关注事业发展，最近更多在聊家庭" |
| `contrast` | 矛盾/对比 | "你说不在乎别人的看法，但你花了很长时间描述他们的反应" |

---

### 2.4 Promise（承诺）

用户的承诺和计划，用于后续跟进。对应现有 `user_commitments` 表。

```sql
CREATE TABLE promise (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    persona         TEXT NOT NULL DEFAULT '默认',
    user_id         TEXT NOT NULL DEFAULT 'local',
    memory_entry_id INTEGER REFERENCES memory_entry(id), -- 来源记忆
    content         TEXT NOT NULL,                     -- 承诺内容

    -- 状态
    status          TEXT NOT NULL DEFAULT 'active',    -- active / completed / abandoned / overdue
    created_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    due_date        TEXT,                              -- 预期完成日期
    completed_at    TEXT,                              -- 实际完成时间

    -- 跟进
    last_check      TEXT,                              -- 最后一次跟进时间
    check_count     INTEGER NOT NULL DEFAULT 0,        -- 跟进次数
    reminder_sent   INTEGER NOT NULL DEFAULT 0,        -- 是否已发送提醒

    is_deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_promise_persona_status ON promise(persona, status);
CREATE INDEX idx_promise_active ON promise(persona, created_at DESC) WHERE status = 'active';
```

---

### 2.5 Snapshot（用户状态快照）

定期或关键节点生成的用户状态摘要，用于跨时间对比。

```sql
CREATE TABLE snapshot (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    persona         TEXT NOT NULL DEFAULT '默认',
    user_id         TEXT NOT NULL DEFAULT 'local',
    snapshot_type   TEXT NOT NULL DEFAULT 'periodic',  -- periodic / milestone / manual

    -- 状态摘要
    emotional_state TEXT,                              -- 当前情绪状态摘要
    key_topics      TEXT,                              -- JSON数组：近期核心话题
    active_patterns TEXT,                              -- JSON数组：当前活跃模式IDs
    current_focus   TEXT,                              -- 当前关注点
    current_tier    INTEGER NOT NULL DEFAULT 1,        -- 当前智者等级

    -- 时间
    created_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    period_start    TEXT,                              -- 覆盖时间段起点
    period_end      TEXT,                              -- 覆盖时间段终点

    is_deleted      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_snapshot_persona_time ON snapshot(persona, created_at DESC);
CREATE INDEX idx_snapshot_persona_type ON snapshot(persona, snapshot_type);
```

---

### 2.6 ConversationSession（会话元数据）

```sql
CREATE TABLE conversation_session (
    id              TEXT PRIMARY KEY,                  -- 会话ID
    persona         TEXT NOT NULL DEFAULT '默认',
    user_id         TEXT NOT NULL DEFAULT 'local',
    started_at      TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    ended_at        TEXT,
    message_count   INTEGER NOT NULL DEFAULT 0,        -- 对话轮次数
    topic           TEXT,                              -- 主要话题
    emotion         TEXT,                              -- 主导情绪
    tier_level      INTEGER NOT NULL DEFAULT 1,        -- 会话期间的Tier级别
    had_insight     INTEGER NOT NULL DEFAULT 0,        -- 是否产生了洞察
    had_pattern     INTEGER NOT NULL DEFAULT 0,        -- 是否识别了新模式
    extracted_count INTEGER NOT NULL DEFAULT 0         -- 从本会话提取的记忆条目数
);

CREATE INDEX idx_session_persona_time ON conversation_session(persona, started_at DESC);
```

---

## 三、Tier 对应的检索策略

| Tier | 检索范围 | 记忆深度 | 说明 |
|------|---------|---------|------|
| L1 倾听者 | 最近 3 条 MemoryEntry | type=event, importance≥3 | 只记住最近发生了什么 |
| L2 共鸣者 | 最近 20 条 MemoryEntry + active Patterns | 所有type, importance≥2 | 开始看见模式 |
| L3 引路人 | 所有 MemoryEntry + Patterns + 未交付 Insights | 全量 | 能说出"你上次也说过类似的话" |
| L4 镜子 | 全量 + Snapshots + 跨时间对比 | 全量+时间轴 | 能说出"三个月前你……现在你……" |

#### 检索伪代码

```kotlin
fun retrieveMemories(persona: String, tier: Int, currentContext: String): List<MemoryEntry> {
    val entries = when (tier) {
        1 -> queryRecent(persona, limit = 3, minImportance = 3.0, types = listOf("event"))
        2 -> queryRecent(persona, limit = 20, minImportance = 2.0)
        3 -> queryAll(persona, minImportance = 1.0)
        4 -> queryAll(persona)
        else -> queryRecent(persona, limit = 3, minImportance = 3.0)
    }

    // 加权：近因效应 × 重要性 × 衰减系数 × 置信度
    val now = LocalDateTime.now()
    for (entry in entries) {
        val recency = timeDecay(entry.createdAt, now)
        entry.score = entry.importance * recency * entry.decayRate * entry.confidence
    }

    // 上下文相关性：当前话题匹配
    return entries.sortedByDescending { it.score }.take(5)
}
```

---

## 四、记忆生命周期

```
对话开始
  ↓ 按 Tier 检索相关记忆，注入 prompt
  ↓ （无名在对话中"看见"之前的记忆）
对话进行中
  ↓ 无名可以在对话中即时写入记忆（用户当场说了重要的事）
对话结束
  ↓ 运行记忆提取器，提炼本轮对话的记忆条目
  ↓ 写入 memory_entry + FTS5
  ↓
每天定时任务
  ↓ 扫描近期 MemoryEntry，发现重复模式
  ↓ 写入 pattern，更新 memory_entry.pattern_id
  ↓
每周定时任务
  ↓ 对比不同时期的 Pattern
  ↓ 写入 insight
  ↓
无名在对话中检索
  ↓ 按 Tier + 上下文 + 重要性 + 衰减 加权检索
注入 prompt，生成回复
  ↓
向用户表达洞察（自然语言）
  ↓
记录 delivered=1，更新 user_reaction
```

---

## 五、衰减策略

| type | 衰减速率 (decay_rate) | 说明 |
|------|----------------------|------|
| event | 0.9 / 30天 | 普通事件慢慢淡化 |
| emotion | 0.8 / 14天 | 情绪时效性强 |
| decision | 0.95 / 30天 | 决定比事件持久，但也会淡化 |
| pattern | 1.0（不衰减） | 模式一旦确认就保留 |
| promise | 1.0（不衰减） | 直到完成或放弃 |
| preference | 1.0（不衰减） | 用户的偏好稳定 |
| observation | 0.85 / 30天 | 无名的观察适度衰减 |
| context | 1.0（不衰减） | 背景信息不衰减 |

#### 特殊规则

- **被访问时**：importance × 1.05（被提及=仍然重要，微微提权）
- **importance=10**：decay_rate 强制设为 1.0（极重要的记忆永不衰减）
- **衰减计算**：`effective_importance = importance × (decay_rate ^ daysSinceCreation)`

---

## 六、与现有架构的关系与升级路径

### 现有 v3 表结构

```
memories（主记忆表：content + layer + category + weight）
user_preferences（偏好表）
user_events（事件表）
user_commitments（承诺表）
archived_memories（归档表）
```

### 映射关系

| 现有表 | 新表 | 变化 |
|--------|------|------|
| memories | memory_entry | 重构：type 取代 layer+category，新增衰减、模式关联、embedding |
| user_preferences | memory_entry (type='preference') | 合并：偏好作为记忆的一种类型 |
| user_events | memory_entry (type='event') | 合并：事件作为记忆的一种类型 |
| user_commitments | promise | 升级：新增跟进机制、来源关联 |
| archived_memories | （删除，改用软删除） | 废弃：is_deleted 取代表级归档 |

### 新增表

- pattern：全新，模式识别的核心
- insight：全新，跨时间洞察
- snapshot：全新，用户状态快照
- conversation_session：全新，会话元数据

### 升级计划：v3 → v4

```
Phase 1：新建新表（不破坏旧数据）
  - 创建 memory_entry、pattern、insight、snapshot、conversation_session 表
  - 创建 promise 表（独立于旧的 user_commitments）

Phase 2：数据迁移
  - memories → memory_entry（根据 category 映射为 type）
  - user_preferences → memory_entry (type='preference')
  - user_events → memory_entry (type='event')
  - user_commitments → promise（字段映射）

Phase 3：切换读写
  - MemoryManager 改为读写新表
  - 旧表保留但标记 deprecated

Phase 4：清理
  - 删除旧表（或保留为 backup）
  - 删除 archived_memories（用软删除替代）
```

---

## 七、FTS5 全文搜索

```sql
-- 记忆条目全文搜索
CREATE VIRTUAL TABLE memory_entry_fts USING fts5(
    content,
    content_short,
    topics,
    content=memory_entry,
    content_rowid=id
);

-- 模式全文搜索
CREATE VIRTUAL TABLE pattern_fts USING fts5(
    description,
    content=pattern,
    content_rowid=id
);

-- 洞察全文搜索
CREATE VIRTUAL TABLE insight_fts USING fts5(
    insight_text,
    content=insight,
    content_rowid=id
);
```

---

## 八、待定项（已确认）

| 项目 | 方案 | 确认人 |
|------|------|--------|
| 话题标签生成 | A. LLM 提取 | 兰兰、雪瑶 |
| 情绪标签生成 | A+B（词典快筛+LLM补充） | 兰兰、雪瑶 |
| 模式识别频率 | B. 每天一次 | 兰兰、雪瑶 |
| Snapshot 生成时机 | A+B+C（每周+Tier升级+关键事件） | 兰兰、雪瑶 |
| 最大条数限制 | C. 按 importance 淘汰 | 兰兰、雪瑶 |
| 关联方式 | C. 两者都要（parent_id + tags） | 兰兰、雪瑶 |

---

## 九、白泽架构影响评估

### 需要改动的代码模块

| 模块 | 改动 | 优先级 | 预估工作量 |
|------|------|--------|-----------|
| **MemoryDbHelper** | 新增 5 张表 + FTS5，版本升级 v3→v4 | 高 | 1天 |
| **MemoryManager** | 重构：新增 memory_entry CRUD + 检索逻辑 | 高 | 2-3天 |
| **记忆提取器**（新模块） | 每轮对话结束时用 LLM 提取记忆条目 | 高 | 2天 |
| **模式识别器**（新模块） | 每天定时扫描 memory_entry，发现 pattern | 中 | 2天 |
| **洞察生成器**（新模块） | 每周对比 pattern，生成 insight | 中 | 1-2天 |
| **Prompt 注入** | 改为按 Tier 检索记忆条目注入 | 高 | 1天 |
| **ChatViewModel** | 对话开始时加载记忆，结束时触发提取 | 高 | 0.5天 |
| **旧表迁移** | v3→v4 数据迁移脚本 | 中 | 1天 |

### 预估总工作量

- Phase 1（核心表+MemoryManager）：4-5 天
- Phase 2（记忆提取器+Prompt注入）：3 天
- Phase 3（模式识别+洞察生成）：3-4 天
- Phase 4（旧表迁移+清理）：1 天
- **总计：约 12-13 天**

---

*版本：v1.0 | 作者：雪瑶 | 审核：兰兰 | 日期：2026-05-27*
*变更记录：v1.0 初版，含全部表结构、检索策略、衰减规则、升级路径*
