# 贡献指南

感谢你对白泽（BaiZe）项目的关注！我们欢迎各种形式的贡献。

## 📋 如何贡献

### 报告 Bug

1. 在 [Issues](https://github.com/your-username/baize/issues) 页面搜索是否已有相同问题
2. 如果没有，创建新的 Issue
3. 请包含以下信息：
   - 问题描述
   - 复现步骤
   - 期望行为
   - 实际行为
   - 设备信息（型号、Android 版本）
   - 截图或日志（如有）

### 提交代码

1. **Fork 仓库**
   ```bash
   git clone https://github.com/your-username/baize.git
   ```

2. **创建功能分支**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **进行修改**
   - 遵循代码规范（见下文）
   - 添加必要的注释
   - 确保代码能通过所有测试

4. **提交更改**
   ```bash
   git commit -m "feat: 添加新功能描述"
   ```

5. **推送到远程**
   ```bash
   git push origin feature/your-feature-name
   ```

6. **创建 Pull Request**
   - 说明修改内容
   - 关联相关 Issue
   - 等待代码审查

## 📝 提交规范

我们使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

| 类型 | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档更新 |
| `style` | 代码格式（不影响功能）|
| `refactor` | 代码重构 |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `chore` | 构建/工具相关 |

示例：
```bash
git commit -m "feat: 添加灵魂文件导入功能"
git commit -m "fix: 修复记忆搜索时的空指针异常"
git commit -m "docs: 更新 README 安装指南"
```

## 🎨 代码规范

### Kotlin

- 使用 4 空格缩进
- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 类名使用 PascalCase
- 函数和变量使用 camelCase
- 常量使用 UPPER_SNAKE_CASE
- 避免使用 `!!`（非空断言），优先使用 `?.` 或 `?:`

### 注释

- 为公共 API 添加 KDoc 注释
- 复杂逻辑添加行内注释
- 避免注释显而易见的代码

```kotlin
/**
 * 加载灵魂文件并解析内容
 *
 * @param soulName 灵魂名称（如 "baize"、"nuannuan"）
 * @return 解析后的灵魂配置，失败返回 null
 */
fun loadSoul(soulName: String): SoulConfig? {
    // ...
}
```

### 命名

- **类名**：名词，如 `SoulManager`、`MemoryDatabase`
- **函数名**：动词开头，如 `loadSoul()`、`sendMessage()`
- **布尔值**：`is`、`has`、`can` 前缀，如 `isLoading`、`hasPermission`
- **常量**：`UPPER_SNAKE_CASE`，如 `MAX_MEMORY_SIZE`

## 🧪 测试

### 运行测试

```bash
# 运行所有测试
./gradlew test

# 运行特定模块测试
./gradlew :app:testDebugUnitTest

# 运行单个测试类
./gradlew :app:testDebugUnitTest --tests "com.baize.ai.soul.core.SoulManagerTest"
```

### 编写测试

- 为新功能添加单元测试
- 测试文件放在 `src/test/` 目录
- 测试类名以 `Test` 结尾
- 测试方法名以 `should` 或 `when` 开头

```kotlin
class SoulManagerTest {
    @Test
    fun `should load soul config from assets`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SoulManager(context)

        // When
        val config = manager.loadSoul("baize")

        // Then
        assertNotNull(config)
        assertEquals("白泽", config?.identity?.name)
    }
}
```

## 🔧 开发环境设置

### 必需工具

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Git

### 可选工具

- NDK (r27) — 用于 llama.cpp JNI
- CMake (3.22+) — 用于构建 native 代码

### 首次设置

1. 安装 Android Studio
2. 配置 JDK 17（File → Project Structure → SDK Location）
3. 克隆仓库
4. 打开项目并等待 Gradle sync
5. 连接设备或启动模拟器
6. 运行应用

## 📚 相关文档

- [架构设计](docs/ARCHITECTURE.md)
- [灵魂文件规范](docs/SOUL-FORMAT.md)
- [记忆系统设计](docs/MEMORY-SCHEMA.md)
- [通信协议](docs/COMM-PROTOCOL.md)

## ❓ 问题帮助

如果遇到问题：

1. 查看 [Issues](https://github.com/your-username/baize/issues) 页面
2. 搜索相关文档
3. 创建 Issue 寻求帮助

## 🎁 贡献者

感谢所有贡献者的支持！

<!-- 添加你的名字和头像 -->
<!-- 
<a href="https://github.com/username">
  <img src="https://github.com/username.png" width="50" height="50" alt="贡献者头像">
</a>
-->

---

*感谢你的贡献！❄️*
