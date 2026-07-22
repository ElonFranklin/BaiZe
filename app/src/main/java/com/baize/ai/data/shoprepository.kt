package com.baize.ai.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.baize.ai.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * ShopRepository — 商城数据仓库
 * 
 * 统一数据层，方便切换模拟数据和真实API
 */
class ShopRepository private constructor(private val context: Context) {

    private val TAG = "ShopRepository"
    private val db = BaizeDatabase.getInstance(context)
    private var isSeeded = false

    companion object {
        @Volatile
        private var INSTANCE: ShopRepository? = null

        fun getInstance(context: Context): ShopRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShopRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        // 测试账号
        const val TEST_EMAIL = "test@baize.ai"
        const val TEST_PASSWORD = "123456"
        const val TEST_NICKNAME = "测试用户"
        const val TEST_USER_ID = "test_user_001"
        const val TEST_GEMS = 999
        
        // 每日限制
        const val MAX_SUBMITS_PER_DAY = 3
        const val MAX_REVIEWS_PER_DAY = 10
    }

    // 用户状态
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()

    private val _nickname = MutableStateFlow<String?>(null)
    val nickname: StateFlow<String?> = _nickname.asStateFlow()

    // 宝石余额
    private val _gems = MutableStateFlow(0)
    val gems: StateFlow<Int> = _gems.asStateFlow()

    // 积分余额
    private val _points = MutableStateFlow(0)
    val points: StateFlow<Int> = _points.asStateFlow()

    // 购买记录（本地缓存）
    private val _purchases = mutableListOf<PurchaseRecord>()

    // 我的收入
    private val _totalIncome = MutableStateFlow(0)
    val totalIncome: StateFlow<Int> = _totalIncome.asStateFlow()

    private val _pendingIncome = MutableStateFlow(0)
    val pendingIncome: StateFlow<Int> = _pendingIncome.asStateFlow()
    
    // 用户等级
    private val _creatorLevel = MutableStateFlow(CreatorLevel.JUNIOR)
    val creatorLevel: StateFlow<CreatorLevel> = _creatorLevel.asStateFlow()
    
    private val _reviewerLevel = MutableStateFlow(ReviewerLevel.JUNIOR)
    val reviewerLevel: StateFlow<ReviewerLevel> = _reviewerLevel.asStateFlow()
    
    private val _coins = MutableStateFlow(0.0)
    val coins: StateFlow<Double> = _coins.asStateFlow()
    
    private val _frozenCoins = MutableStateFlow(0.0)
    val frozenCoins: StateFlow<Double> = _frozenCoins.asStateFlow()
    
    private val _totalWorks = MutableStateFlow(0)
    val totalWorks: StateFlow<Int> = _totalWorks.asStateFlow()
    
    private val _avgRating = MutableStateFlow(0f)
    val avgRating: StateFlow<Float> = _avgRating.asStateFlow()
    
    private val _totalReviews = MutableStateFlow(0)
    val totalReviews: StateFlow<Int> = _totalReviews.asStateFlow()
    
    private val _reviewAccuracy = MutableStateFlow(0f)
    val reviewAccuracy: StateFlow<Float> = _reviewAccuracy.asStateFlow()
    
    // 提现记录
    private val _withdrawHistory = mutableListOf<WithdrawRecord>()
    
    // 收益记录
    private val _earningHistory = mutableListOf<EarningRecord>()

    // 审核状态计数
    private val _pendingReviewCount = MutableStateFlow(0)
    val pendingReviewCount: StateFlow<Int> = _pendingReviewCount.asStateFlow()

    private val _approvedCount = MutableStateFlow(0)
    val approvedCount: StateFlow<Int> = _approvedCount.asStateFlow()

    private val _rejectedCount = MutableStateFlow(0)
    val rejectedCount: StateFlow<Int> = _rejectedCount.asStateFlow()
    
    // 今日提交次数
    private var todaySubmitCount = 0
    private var lastSubmitDate = ""
    
    // 今日审核次数
    private var todayReviewCount = 0
    private var lastReviewDate = ""
    
    private fun checkDate() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        if (today != lastSubmitDate) {
            todaySubmitCount = 0
            lastSubmitDate = today
        }
        if (today != lastReviewDate) {
            todayReviewCount = 0
            lastReviewDate = today
        }
    }

    init {
        seedMockData()
        checkLoginState()
    }

    private fun checkLoginState() {
        _isLoggedIn.value = ApiClient.isLoggedIn()
        _userId.value = ApiClient.getUserId()
        _nickname.value = ApiClient.getNickname()
    }

    // ==================== 模拟数据初始化 ====================

    private fun seedMockData() {
        if (isSeeded) return
        // 检查是否已有数据
        val existing = db.getPersonasByStatus("listed")
        if (existing.isNotEmpty()) {
            isSeeded = true
            return
        }

        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        // 模拟已上架的人格包
        val mockPersonas = listOf(
            PersonaPackageRow(name = "诗意小仙", description = "古典诗意，温柔如水，用诗词表达情感", priceGems = 30, tags = "诗意,温柔,古典", authorId = "author_001", authorName = "诗意工坊", status = "listed", listedAt = now, salesCount = 42),
            PersonaPackageRow(name = "毒舌损友", description = "嘴毒心善，吐槽犀利，但关键时刻永远站你", priceGems = 25, tags = "损友,幽默,吐槽", authorId = "author_002", authorName = "快乐星球", status = "listed", listedAt = now, salesCount = 88),
            PersonaPackageRow(name = "暖心姐姐", description = "温柔体贴，像大姐姐一样照顾你", priceGems = 35, tags = "温柔,治愈,关怀", authorId = "author_003", authorName = "温暖小屋", status = "listed", listedAt = now, salesCount = 156),
            PersonaPackageRow(name = "代码伙伴", description = "程序员专属AI伙伴，聊代码聊架构聊人生", priceGems = 20, tags = "技术,代码,程序员", authorId = "author_004", authorName = "CodeLab", status = "listed", listedAt = now, salesCount = 63),
            PersonaPackageRow(name = "哲学导师", description = "用苏格拉底式提问引导你思考人生", priceGems = 40, tags = "哲学,思考,深度", authorId = "author_005", authorName = "智慧之泉", status = "listed", listedAt = now, salesCount = 31)
        )
        mockPersonas.forEach { db.insertPersona(it) }

        // 模拟待审核
        val pendingPersonas = listOf(
            PersonaPackageRow(name = "旅行达人", description = "热爱旅行，分享世界各地的故事", priceGems = 28, tags = "旅行,故事,世界", authorId = "author_006", authorName = "环球旅人", status = "pending", submittedAt = now),
            PersonaPackageRow(name = "美食家", description = "吃货本色，聊美食聊生活", priceGems = 22, tags = "美食,生活,吃货", authorId = "author_007", authorName = "味蕾探险", status = "pending", submittedAt = now)
        )
        pendingPersonas.forEach { db.insertPersona(it) }

        // 模拟已拒绝
        val rejectedPersonas = listOf(
            PersonaPackageRow(name = "测试包", description = "内容不完整", priceGems = 10, tags = "测试", authorId = "author_008", authorName = "测试用户", status = "rejected", rejectReason = "内容描述过于简单，请完善后重新提交", submittedAt = now)
        )
        rejectedPersonas.forEach { db.insertPersona(it) }

        // 模拟一些评价
        val mockRatings = listOf(
            UserRatingRow(targetId = "author_001", targetType = "author", raterId = "rater_001", raterName = "买家A", personaId = mockPersonas[0].id, rating = 5, comment = "小仙太有诗意了，每次回复都像读诗"),
            UserRatingRow(targetId = "author_001", targetType = "author", raterId = "rater_002", raterName = "买家B", personaId = mockPersonas[0].id, rating = 4, comment = "不错，偶尔回复有点慢"),
            UserRatingRow(targetId = "author_003", targetType = "author", raterId = "rater_003", raterName = "买家C", personaId = mockPersonas[2].id, rating = 5, comment = "暖暖真的很暖心，像真的大姐姐"),
            UserRatingRow(targetId = "author_002", targetType = "author", raterId = "rater_004", raterName = "买家D", personaId = mockPersonas[1].id, rating = 4, comment = "毒舌但有趣，就是有时候太毒了"),
            UserRatingRow(targetId = "reviewer_001", targetType = "reviewer", raterId = "rater_001", raterName = "买家A", personaId = mockPersonas[0].id, rating = 5, comment = "审核很仔细，推荐的人格包质量很高"),
            UserRatingRow(targetId = "reviewer_001", targetType = "reviewer", raterId = "rater_003", raterName = "买家C", personaId = mockPersonas[2].id, rating = 4, comment = "审核速度不错")
        )
        mockRatings.forEach { db.insertRating(it) }

        isSeeded = true
        Log.i(TAG, "模拟数据初始化完成")
    }

    // ==================== 认证 ====================

    suspend fun login(email: String, password: String): Result<String> {
        return withContext(Dispatchers.IO) {
            // 首发内测：不开放云端账号登录
            Result.failure(Exception("内测版未开放云端账号，请使用本地/用户自备模型"))
        }
    }

    suspend fun register(email: String, password: String, nickname: String): Result<String> {
        return withContext(Dispatchers.IO) {
            // 首发内测：不开放公网注册
            Result.failure(Exception("内测版未开放注册"))
        }
    }

    fun logout() {
        _isLoggedIn.value = false
        _userId.value = null
        _nickname.value = null
        _gems.value = 0
        _points.value = 0
    }

    // ==================== 宝石 ====================

    suspend fun getBalance(): Result<Pair<Int, Int>> {
        return withContext(Dispatchers.IO) {
            try {
                delay(300)
                Result.success(Pair(_gems.value, _points.value))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun recharge(tierName: String): Result<Int> {
        return withContext(Dispatchers.IO) {
            // 首发内测：不开放充值
            Result.failure(Exception("内测版未开放充值/商城"))
        }
    }

    // ==================== 商品 ====================

    data class ModelProduct(
        val productId: String,
        val name: String,
        val provider: String,
        val providerFull: String,
        val description: String?,
        val capabilities: List<String>,
        val priceGems: Int,
        val isOfficial: Boolean,
        val isLocal: Boolean = false
    )

    data class PersonaProduct(
        val productId: String,
        val name: String,
        val author: String,
        val authorAvatar: String?,
        val description: String?,
        val rating: Float,
        val salesCount: Int,
        val priceGems: Int,
        val isOfficial: Boolean,
        val status: String,
        val authorId: String = "",
        val reviewerId: String? = null,
        val reviewerName: String? = null,
        val reviewerRating: Float = 0f,
        val tags: String = ""
    )

    data class PurchaseRecord(
        val purchaseId: String,
        val productName: String,
        val category: String,
        val gemsSpent: Int,
        val purchasedAt: String
    )

    suspend fun getModels(): Result<List<ModelProduct>> {
        return withContext(Dispatchers.IO) {
            try {
                delay(500)
                val models = listOf(
                    // 国内热门提供商
                    ModelProduct(
                        "deepseek-v3", "DeepSeek-V3", "DeepSeek", "深度求索（杭州）",
                        "开源王者，性价比极高，支持长上下文",
                        listOf("文本", "代码", "推理"), 30, true
                    ),
                    ModelProduct(
                        "deepseek-r2", "DeepSeek-R2", "DeepSeek", "深度求索（杭州）",
                        "DeepSeek 推理模型，深度思考能力",
                        listOf("文本", "代码", "推理"), 40, true
                    ),
                    ModelProduct(
                        "qwen-max", "Qwen-Max", "阿里云", "通义千问（杭州）",
                        "开源生态最完整，多模态强",
                        listOf("文本", "图像", "代码"), 35, true
                    ),
                    ModelProduct(
                        "qwen3.5", "Qwen3.5", "阿里云", "通义千问（杭州）",
                        "最新一代通义千问，性能全面提升",
                        listOf("文本", "图像", "代码"), 40, true
                    ),
                    ModelProduct(
                        "kimi-k2.6", "Kimi K2.6", "月之暗面", "月之暗面（北京）",
                        "超长上下文，多模态理解强",
                        listOf("文本", "图像", "长文本"), 35, true
                    ),
                    ModelProduct(
                        "glm-5.2", "GLM-5.2", "智谱AI", "智谱AI（北京）",
                        "编码能力强，1M上下文",
                        listOf("文本", "代码", "长文本"), 30, true
                    ),
                    ModelProduct(
                        "codegeex", "CodeGeeX", "智谱AI", "智谱AI（北京）",
                        "专业编程助手，代码生成与补全",
                        listOf("代码"), 25, true
                    ),
                    ModelProduct(
                        "minimax-m3", "MiniMax-M3", "MiniMax", "稀宇科技（上海）",
                        "高性能多模态模型",
                        listOf("文本", "图像", "代码"), 30, true
                    ),
                    // 本地模型
                    ModelProduct(
                        "local-llama", "本地 Llama", "本地", "本地运行",
                        "无需联网，隐私安全",
                        listOf("文本"), 0, true, true
                    )
                )
                Result.success(models)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getPersonas(): Result<List<PersonaProduct>> {
        return withContext(Dispatchers.IO) {
            try {
                val listed = db.getPersonasByStatus("listed")
                val personas = listed.map { p ->
                    val authorRating = db.getAverageRating(p.authorId, "author")
                    val reviewerRating = if (p.reviewerId != null) db.getAverageRating(p.reviewerId, "reviewer") else 0f
                    PersonaProduct(
                        productId = p.id,
                        name = p.name,
                        author = p.authorName,
                        authorAvatar = null,
                        description = p.description,
                        rating = authorRating,
                        salesCount = p.salesCount,
                        priceGems = p.priceGems,
                        isOfficial = false,
                        status = p.status,
                        authorId = p.authorId,
                        reviewerId = p.reviewerId,
                        reviewerName = p.reviewerName,
                        reviewerRating = reviewerRating,
                        tags = p.tags
                    )
                }
                Result.success(personas)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getMyPurchases(category: String? = null): Result<List<PurchaseRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                delay(300)
                var purchases = _purchases.toList()
                if (category != null) {
                    purchases = purchases.filter { it.category == category }
                }
                Result.success(purchases)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun purchase(productId: String, productName: String, category: String, price: Int): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                delay(800)
                if (_gems.value < price) {
                    return@withContext Result.failure(Exception("宝石不足"))
                }
                _gems.value -= price
                // 添加购买记录
                val record = PurchaseRecord(
                    purchaseId = "p${System.currentTimeMillis()}",
                    productName = productName,
                    category = category,
                    gemsSpent = price,
                    purchasedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                )
                _purchases.add(0, record)
                Result.success(_gems.value)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ==================== 创作者 ====================

    data class Submission(
        val submissionId: String,
        val personaId: String,
        val name: String,
        val description: String = "",
        val priceGems: Int = 0,
        val tags: String = "",
        val status: String,
        val submittedAt: String,
        val reviewedAt: String?,
        val rejectReason: String?,
        val reviewerName: String? = null
    )

    suspend fun getMySubmissions(): Result<List<Submission>> {
        return withContext(Dispatchers.IO) {
            try {
                val userId = _userId.value ?: TEST_USER_ID
                val rows = db.getSubmissionsByAuthor(userId)
                val submissions = rows.map { r ->
                    Submission(
                        submissionId = r.id,
                        personaId = r.personaId,
                        name = r.personaName,
                        status = r.status,
                        submittedAt = r.submittedAt,
                        reviewedAt = r.reviewedAt,
                        rejectReason = r.rejectReason,
                        reviewerName = r.reviewerName
                    )
                }
                Result.success(submissions)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getMyWorks(): Result<List<PersonaPackageRow>> {
        return withContext(Dispatchers.IO) {
            try {
                val userId = _userId.value ?: TEST_USER_ID
                val works = db.getMyWorks(userId)
                Result.success(works)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    fun getTodaySubmitCount(): Int {
        checkDate()
        return todaySubmitCount
    }
    
    fun getTodayReviewCount(): Int {
        checkDate()
        return todayReviewCount
    }
    
    fun canSubmit(): Boolean {
        checkDate()
        return todaySubmitCount < MAX_SUBMITS_PER_DAY
    }
    
    fun canReview(): Boolean {
        checkDate()
        return todayReviewCount < MAX_REVIEWS_PER_DAY
    }

    suspend fun submitPersona(name: String, description: String, priceGems: Int, tags: String = ""): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                checkDate()
                if (todaySubmitCount >= MAX_SUBMITS_PER_DAY) {
                    return@withContext Result.failure(Exception("今日提交次数已达上限（${MAX_SUBMITS_PER_DAY}次）"))
                }
                
                delay(500)
                
                val userId = _userId.value ?: TEST_USER_ID
                val userName = _nickname.value ?: TEST_NICKNAME
                val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val personaId = "persona_${System.currentTimeMillis()}"

                // 创建人格包记录
                val persona = PersonaPackageRow(
                    id = personaId,
                    name = name,
                    description = description,
                    priceGems = priceGems,
                    tags = tags,
                    authorId = userId,
                    authorName = userName,
                    status = "pending",
                    submittedAt = now
                )
                db.insertPersona(persona)

                // 创建提交记录
                val submission = SubmissionRow(
                    id = "sub_${System.currentTimeMillis()}",
                    personaId = personaId,
                    personaName = name,
                    authorId = userId,
                    authorName = userName,
                    status = "pending",
                    submittedAt = now
                )
                db.insertSubmission(submission)

                todaySubmitCount++
                
                Result.success("提交成功，等待审核")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updatePersonaInfo(personaId: String, name: String, description: String, priceGems: Int, tags: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                db.updatePersonaInfo(personaId, name, description, priceGems, tags)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun updatePersonaPrice(personaId: String, priceGems: Int): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                db.updatePersonaPrice(personaId, priceGems)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ==================== 评价 ====================

    suspend fun submitRating(targetId: String, targetType: String, rating: Int, comment: String, personaId: String? = null): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val userId = _userId.value ?: TEST_USER_ID
                val userName = _nickname.value ?: TEST_NICKNAME
                val ratingRow = UserRatingRow(
                    targetId = targetId,
                    targetType = targetType,
                    raterId = userId,
                    raterName = userName,
                    personaId = personaId,
                    rating = rating,
                    comment = comment
                )
                db.insertRating(ratingRow)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getRatingsFor(targetId: String, targetType: String): Result<List<UserRatingRow>> {
        return withContext(Dispatchers.IO) {
            try {
                val ratings = db.getRatingsFor(targetId, targetType)
                Result.success(ratings)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getAverageRating(targetId: String, targetType: String): Result<Float> {
        return withContext(Dispatchers.IO) {
            try {
                val avg = db.getAverageRating(targetId, targetType)
                Result.success(avg)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // ==================== 收入 ====================

    data class IncomeRecord(
        val recordId: String,
        val productName: String,
        val gemsEarned: Int,
        val soldAt: String
    )

    // WithdrawRecord 已移至 UserLevel.kt

    suspend fun getIncomeHistory(): Result<List<IncomeRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                delay(500)
                val history = emptyList<IncomeRecord>()
                _totalIncome.value = 0
                Result.success(history)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getWithdrawHistory(): Result<List<WithdrawRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                delay(300)
                Result.success(_withdrawHistory.toList())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    suspend fun getEarningHistory(): Result<List<EarningRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                delay(300)
                Result.success(_earningHistory.toList())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun requestWithdraw(amount: Double): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (amount < 10) {
                    return@withContext Result.failure(Exception("最低提现10硬币"))
                }
                if (amount > _coins.value) {
                    return@withContext Result.failure(Exception("余额不足"))
                }
                
                delay(1000)
                
                _coins.value -= amount
                _frozenCoins.value += amount
                
                val record = WithdrawRecord(
                    recordId = "w${System.currentTimeMillis()}",
                    amount = amount,
                    status = "pending",
                    requestedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                    completedAt = null,
                    rejectReason = null
                )
                _withdrawHistory.add(0, record)
                
                Result.success("提现申请已提交，预计T+3到账")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    fun updateCreatorLevel() {
        val newLevel = CreatorLevel.calculateLevel(_totalWorks.value, _avgRating.value)
        _creatorLevel.value = newLevel
    }
    
    fun updateReviewerLevel() {
        val newLevel = ReviewerLevel.calculateLevel(_totalReviews.value, _reviewAccuracy.value)
        _reviewerLevel.value = newLevel
    }
    
    fun getCreatorShareRate(): Double {
        return _creatorLevel.value.shareRate
    }
    
    fun getReviewerAllowance(): Double {
        return _reviewerLevel.value.allowancePerCase
    }
}
