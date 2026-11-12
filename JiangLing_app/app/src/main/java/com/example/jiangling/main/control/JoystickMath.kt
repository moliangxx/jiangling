package com.example.jiangling.main.control

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * ======================================================================
 *  轮盘方位判定与分量换算（纯函数）
 * ======================================================================
 *
 *  这一层刻意不依赖 Compose / Android，只吃「触点相对轮盘中心的坐标」，
 *  输出「八方位 + 两个带符号的轴分量」，方便脱离界面单独验证：
 *
 *      val v = resolveJoystick(dx = 30f, dy = -30f, radius = 50f)
 *      // v.way == FORWARD_RIGHT, v.forward == +0.85, v.turn == -0.85
 *
 *  **分量方向按定稿协议（0xEB / 0xEC）定义，不是按屏幕左右定义**：
 *      forward > 0 前进 / < 0 后退（对应 0xEB 的 drive）
 *      turn    > 0 左转 / < 0 右转（对应 0xEB 的 turn；云台 pan 取反后下发）
 *
 *  角度定义（屏幕坐标 y 轴向下）：
 *      0°   = 正前（屏幕上方），顺时针为正，取值范围 -180°~180°
 *      -45° = 左前      +45° = 右前
 *      -90° = 正左      +90° = 正右
 *      ±135°= 左后/右后  ±180° = 正后
 *
 *  八个方位各占 45°，分界线在 22.5 / 67.5 / 112.5 / 157.5°。
 * ======================================================================
 */

/** 死区：拖拽幅度小于半径的这个比例视为回中，不发方向 */
const val JOYSTICK_DEAD_ZONE = 0.22f

/** 统一节拍：按住期间约 200ms 下发一帧（手指不动也重发，防板子超时自停） */
const val SEND_PERIOD_MS = 200L

/** 八方位 */
enum class DriveWay {
    NONE,
    FORWARD,
    BACKWARD,
    FORWARD_LEFT,
    FORWARD_RIGHT,
    BACKWARD_LEFT,
    BACKWARD_RIGHT,
    LEFT,
    RIGHT;

    /**
     * 正左 / 正右需要横向平移，差速车做不到：
     * 命中时**不下发任何命令**，只让轮盘给限位提示。
     */
    val isLateral: Boolean get() = this == LEFT || this == RIGHT
}

/**
 * 轮盘解算结果。
 *
 * @param forward 纵向分量：+ 前进 / - 后退，绝对值 0..1（协议 `drive` 的方向）
 * @param turn    转向分量：**+ 左转 / - 右转**，绝对值 0..1（协议 `turn` 的方向）
 * @param magnitude 归一化拖拽幅度 0..1，用于换算速度
 */
data class JoystickVector(
    val way: DriveWay,
    val forward: Float,
    val turn: Float,
    val magnitude: Float
) {
    companion object {
        val IDLE = JoystickVector(DriveWay.NONE, 0f, 0f, 0f)
    }
}

/**
 * 纯函数：触点相对轮盘中心的坐标 → 方位 + 两个轴分量。
 *
 * 斜向方位同时给出纵向与横向两个分量（例如右前 = forward 为正、turn 为负），
 * 两个分量装进同一帧 `0xEB`（移动）或 `0xEC`（云台）一起下发，由固件按两轴合成。
 *
 * @param dx 触点相对圆心的水平偏移（像素，右为正）
 * @param dy 触点相对圆心的垂直偏移（像素，下为正）
 * @param radius 轮盘半径（像素）
 * @param deadZone 死区比例，默认 [JOYSTICK_DEAD_ZONE]
 */
fun resolveJoystick(
    dx: Float,
    dy: Float,
    radius: Float,
    deadZone: Float = JOYSTICK_DEAD_ZONE
): JoystickVector {
    if (radius <= 0f) return JoystickVector.IDLE

    val distance = hypot(dx, dy)
    if (distance < radius * deadZone) return JoystickVector.IDLE

    val magnitude = (distance / radius).coerceIn(0f, 1f)
    // 屏幕 y 轴向下，取反后 0° 才是正前
    val angle = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    val absAngle = abs(angle)

    val way = when {
        absAngle <= 22.5f -> DriveWay.FORWARD
        absAngle <= 67.5f -> if (angle > 0f) DriveWay.FORWARD_RIGHT else DriveWay.FORWARD_LEFT
        absAngle <= 112.5f -> if (angle > 0f) DriveWay.RIGHT else DriveWay.LEFT
        absAngle <= 157.5f -> if (angle > 0f) DriveWay.BACKWARD_RIGHT else DriveWay.BACKWARD_LEFT
        else -> DriveWay.BACKWARD
    }

    val forwardSign = when (way) {
        DriveWay.FORWARD, DriveWay.FORWARD_LEFT, DriveWay.FORWARD_RIGHT -> 1f
        DriveWay.BACKWARD, DriveWay.BACKWARD_LEFT, DriveWay.BACKWARD_RIGHT -> -1f
        else -> 0f
    }
    /*
     * 转向符号按定稿协议 `0xEB.turn`：正 = 左转（俯视逆时针）。
     *
     * 前进时「往左打方向」= turn 正；后退时车尾朝外摆，同一个物理转向对应的
     * 轮速差方向相反，所以后退两格与前进两格符号**相反**：
     *   左前 +T / 右前 −T / 左后 −T / 右后 +T（见 命令.md §「八方位映射」）
     *
     * 正左 / 正右也给出纯转向分量（forward=0、turn=±T）：差速车做不到横移，
     * 移动轮盘按 [DriveWay.isLateral] 拦截不下发；但云台 `0xEC` 需要用它做水平偏航。
     */
    val turnSign = when (way) {
        DriveWay.FORWARD_LEFT, DriveWay.BACKWARD_RIGHT, DriveWay.LEFT -> 1f
        DriveWay.FORWARD_RIGHT, DriveWay.BACKWARD_LEFT, DriveWay.RIGHT -> -1f
        else -> 0f
    }

    return JoystickVector(
        way = way,
        forward = forwardSign * magnitude,
        turn = turnSign * magnitude,
        magnitude = magnitude
    )
}
