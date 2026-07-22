# 白泽设置页重构方案

## 现状分析

当前 `SettingsActivity.kt` 有 **1142 行**，`activity_settings.xml` 有 **628 行**。
所有功能平铺在一个 ScrollView 里，用户需要不断滚动才能找到想要的设置项。

## 重构方案

### 架构设计

采用 **主列表 → 子页面** 的两级结构：

```
SettingsHubActivity (主列表)
├── 对话设置 → ChatSettingsFragment
├── 语音设置 → VoiceSettingsFragment
├── 人格与记忆 → PersonaSettingsFragment
├── 模型配置 → ModelSettingsFragment
├── 🛒 商城 → ShopActivity (已有)
├── 存储与安全 → StorageSettingsFragment
└── 关于 → AboutSettingsFragment
```

### 主页面 UI (activity_settings_hub.xml)

```
┌─────────────────────────────────┐
│  ← 白泽设置                     │
├─────────────────────────────────┤
│  💬  对话设置                    │
│      三档切换、历史搜索、聊天加密  │
│─────────────────────────────────│
│  🎤  语音设置                    │
│      TTS、语音角色、语速          │
│─────────────────────────────────│
│  👤  人格与记忆                  │
│      14种灵魂、记忆健康、做梦     │
│─────────────────────────────────│
│  ⚙️  模型配置                    │
│      API、模型选择、本地模型      │
│─────────────────────────────────│
│  🛒  商城                        │
│      模型选购、人格交易           │
│─────────────────────────────────│
│  💾  存储与安全                  │
│      导入导出、清除、加密         │
│─────────────────────────────────│
│  ℹ️  关于                        │
│      版本、协议、反馈             │
└─────────────────────────────────┘
```

### 各子页面功能分配

#### 1. 对话设置 (ChatSettingsFragment)
- 对话档位：轻聊/标准/深度 + 描述
- 对话历史搜索
- 聊天记录加密开关

#### 2. 语音设置 (VoiceSettingsFragment)
- TTS 开关
- 语音角色选择
- 语速调节 (0.5x~2.0x SeekBar)
- 语音输入开关

#### 3. 人格与记忆 (PersonaSettingsFragment)
- 当前人格显示 + 切换
- 人格摘要
- 记忆健康检查
- 头像设置

#### 4. 模型配置 (ModelSettingsFragment)
- 推理模式：云端/本地
- 云端 API 配置（选择、新增、编辑、删除）
- 本地模型选择 + 刷新
- 思考模式
- 测试连接

#### 5. 商城 (ShopActivity)
- 模型选购
- 人格交易
- 已有代码，独立 Activity

#### 6. 存储与安全 (StorageSettingsFragment)
- 人格包导入导出
- 剪贴板导入导出
- 对话记录导入导出
- 清空对话
- 聊天记录加密状态

#### 7. 关于 (AboutSettingsFragment)
- 版本信息
- 开源协议
- 反馈入口

## 文件结构

```
app/src/main/
├── java/com/baize/ai/ui/settings/
│   ├── SettingsHubActivity.kt        (新 - 主列表)
│   ├── ChatSettingsFragment.kt       (新 - 对话设置)
│   ├── VoiceSettingsFragment.kt      (新 - 语音设置)
│   ├── PersonaSettingsFragment.kt    (新 - 人格与记忆)
│   ├── ModelSettingsFragment.kt      (新 - 模型配置)
│   ├── StorageSettingsFragment.kt    (新 - 存储与安全)
│   ├── AboutSettingsFragment.kt      (新 - 关于)
│   ├── SettingsActivity.kt           (旧 - 保留兼容)
│   ├── ChatTierManager.kt            (保留)
│   ├── SoulEditorActivity.kt         (保留)
│   └── SoulFilesActivity.kt          (保留)
└── res/layout/
    ├── activity_settings_hub.xml     (新 - 主列表)
    ├── fragment_chat_settings.xml    (新)
    ├── fragment_voice_settings.xml   (新)
    ├── fragment_persona_settings.xml (新)
    ├── fragment_model_settings.xml   (新)
    ├── fragment_storage_settings.xml (新)
    └── fragment_about_settings.xml   (新)
```

## 实施步骤

1. 创建 `SettingsHubActivity` + `activity_settings_hub.xml`
2. 逐个创建 Fragment + 对应 XML
3. 修改 `AndroidManifest.xml` 注册新 Activity
4. 修改 `MainActivity.kt` 启动入口指向新 Hub
5. 逐个迁移逻辑到对应 Fragment
6. 测试 + 清理旧代码

## 设计规范

- 暗色主题：背景 #1E1E1E，文字 #FFFFFF，次要文字 #AAAAAA
- 列表项高度 72dp，左侧 emoji 图标，右侧箭头
- 分隔线 #333333
- 字体大小：标题 18sp，描述 12sp
