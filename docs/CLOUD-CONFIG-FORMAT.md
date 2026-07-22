# .baize-cloud 文件格式规范 v1.0

> 白泽云端配置包格式 | 2026-05-27

---

## 一、概述

`.baize-cloud` 是白泽的云端配置包文件格式，用于导入/导出 API 配置（endpoint、key、model）。

**设计目标：**
- 安全：API Key 加密存储，需要密码才能解密
- 可移植：纯 JSON 文本，可分享、可上传
- 可校验：SHA-256 校验和防止篡改
- 向后兼容：版本号机制，新版本可读旧格式

---

## 二、文件结构

```json
{
  "version": 1,
  "format": "baize-cloud",
  "name": "配置包名称",
  "description": "可选描述",
  "author": "作者",
  "configs": [
    {
      "name": "配置名称",
      "baseUrl": "https://api.example.com/v1",
      "apiKey": "加密后的 API Key（Base64）",
      "model": "gpt-4o",
      "reasoningLevel": "none"
    }
  ],
  "salt": "加密盐（Base64）",
  "iv": "初始化向量（Base64）",
  "checksum": "SHA-256 校验和（Base64）"
}
```

---

## 三、字段说明

### 顶层字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `version` | int | ✅ | 格式版本号（当前为 1） |
| `format` | string | ✅ | 固定值 `"baize-cloud"` |
| `name` | string | ✅ | 配置包名称（用户自定义） |
| `description` | string | ❌ | 描述信息 |
| `author` | string | ❌ | 作者 |
| `configs` | array | ✅ | 配置数组（至少 1 项） |
| `salt` | string | ✅ | PBKDF2 加密盐（Base64） |
| `iv` | string | ✅ | AES-CBC 初始化向量（Base64） |
| `checksum` | string | ✅ | 完整性校验（SHA-256） |

### configs 数组元素

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 配置名称（如 "OpenAI"、"DeepSeek"） |
| `baseUrl` | string | ✅ | API 端点（如 "https://api.openai.com/v1"） |
| `apiKey` | string | ✅ | 加密后的 API Key（AES-256-CBC + Base64） |
| `model` | string | ✅ | 模型 ID（如 "gpt-4o"、"deepseek-chat"） |
| `reasoningLevel` | string | ❌ | 推理级别："none" / "low" / "medium" / "high" |

---

## 四、加密方案

### 算法

| 参数 | 值 |
|------|-----|
| 对称加密 | AES-256-CBC |
| 密钥派生 | PBKDF2WithHmacSHA256 |
| 迭代次数 | 10,000 |
| 密钥长度 | 256 bit |
| IV 长度 | 128 bit |
| 填充 | PKCS5Padding |

### 流程

```
用户密码
  ↓ PBKDF2(password, salt, 10000, 256)
  ↓ 生成 AES-256 密钥
  ↓
API Key（明文）
  ↓ AES-CBC-Encrypt(key, iv, apiKey)
  ↓ Base64 编码
  ↓
加密后的 apiKey（存入 JSON）
```

### 校验和

```
checksum = Base64(SHA-256(JSON不含checksum字段))
```

导入时重新计算 checksum 并比对，防止文件被篡改。

---

## 五、导入流程

```
用户选择 .baize-cloud 文件
  ↓
读取 JSON，校验 format + version
  ↓
校验 checksum（防篡改）
  ↓
提示用户输入密码
  ↓
PBKDF2 派生密钥 → AES 解密 configs[].apiKey
  ↓（密码错误 → 解密失败 → 提示密码错误）
逐个检查是否已存在相同配置
  ↓
保存到 SharedPreferences
  ↓
可选：立即激活第一个配置
```

---

## 六、导出流程

```
读取当前所有/指定配置
  ↓
用户设置密码 + 配置包名称
  ↓
PBKDF2 派生密钥 → AES 加密每个 apiKey
  ↓
组装 JSON + 计算 checksum
  ↓
写入 .baize-cloud 文件
```

---

## 七、使用场景

### 场景 1：用户备份自己的配置
"我换了手机，想把云端配置迁移过去"
→ 导出 .baize-cloud → 传到新手机 → 导入

### 场景 2：分享配置给朋友
"我找到了一个好用的 API，分享给你"
→ 导出 .baize-cloud（设密码）→ 发给朋友 → 朋友导入

### 场景 3：配置商城（未来）
"一键获取推荐配置包"
→ 下载 .baize-cloud → 导入 → 即用

---

*版本：v1.0 | 日期：2026-05-27*
