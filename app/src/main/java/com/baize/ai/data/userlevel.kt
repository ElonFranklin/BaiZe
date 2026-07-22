package com.baize.ai.data

/**
 * 创作者等级系统
 */
enum class CreatorLevel(
    val level: Int,
    val displayName: String,
    val shareRate: Double,  // 分成比例
    val minWorks: Int,      // 最少上架数
    val minRating: Float    // 最低评分
) {
    JUNIOR(1, "初级创作者", 0.70, 0, 0f),
    SENIOR(2, "高级创作者", 0.80, 10, 4.5f),
    EXPERT(3, "专家级创作者", 0.90, 50, 4.8f);

    companion object {
        fun fromLevel(level: Int): CreatorLevel {
            return when (level) {
                2 -> SENIOR
                3 -> EXPERT
                else -> JUNIOR
            }
        }

        fun calculateLevel(totalWorks: Int, avgRating: Float): CreatorLevel {
            return when {
                totalWorks >= EXPERT.minWorks && avgRating >= EXPERT.minRating -> EXPERT
                totalWorks >= SENIOR.minWorks && avgRating >= SENIOR.minRating -> SENIOR
                else -> JUNIOR
            }
        }
    }

    fun getShareAmount(salePrice: Int): Int {
        return (salePrice * shareRate).toInt()
    }

    fun getPlatformFee(salePrice: Int): Int {
        return salePrice - getShareAmount(salePrice)
    }
}

/**
 * 审核者等级系统
 */
enum class ReviewerLevel(
    val level: Int,
    val displayName: String,
    val allowancePerCase: Double,  // 每件津贴（硬币）
    val minReviews: Int,           // 最少审核数
    val minAccuracy: Float         // 最低准确率
) {
    JUNIOR(1, "初级审核员", 0.5, 0, 0f),
    SENIOR(2, "高级审核员", 1.0, 100, 0.95f),
    EXPERT(3, "专家级审核员", 1.5, 500, 0.98f);

    companion object {
        fun fromLevel(level: Int): ReviewerLevel {
            return when (level) {
                2 -> SENIOR
                3 -> EXPERT
                else -> JUNIOR
            }
        }

        fun calculateLevel(totalReviews: Int, accuracy: Float): ReviewerLevel {
            return when {
                totalReviews >= EXPERT.minReviews && accuracy >= EXPERT.minAccuracy -> EXPERT
                totalReviews >= SENIOR.minReviews && accuracy >= SENIOR.minAccuracy -> SENIOR
                else -> JUNIOR
            }
        }
    }

    fun getAllowance(reviewsCount: Int): Double {
        return reviewsCount * allowancePerCase
    }
}

/**
 * 用户账户信息
 */
data class UserAccount(
    val userId: String,
    val nickname: String,
    val email: String,
    val gems: Int,              // 宝石余额
    val points: Int,            // 积分余额
    val coins: Double,          // 硬币余额（可提现）
    val frozenCoins: Double,    // 冻结余额（未结算）
    val creatorLevel: CreatorLevel,
    val reviewerLevel: ReviewerLevel,
    val totalWorks: Int,        // 上架作品数
    val avgRating: Float,       // 平均评分
    val totalReviews: Int,      // 审核总数
    val reviewAccuracy: Float   // 审核准确率
)

/**
 * 提现记录
 */
data class WithdrawRecord(
    val recordId: String,
    val amount: Double,         // 提现金额（硬币）
    val status: String,         // pending/completed/rejected
    val requestedAt: String,
    val completedAt: String?,
    val rejectReason: String?
)

/**
 * 收益记录
 */
data class EarningRecord(
    val recordId: String,
    val type: String,           // sale/allowance/bonus
    val amount: Double,
    val description: String,
    val createdAt: String
)
