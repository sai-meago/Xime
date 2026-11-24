package com.kingzcheung.xime.calculator

private val calculatorKeyPattern = Regex("[0-9]")
private val calculatorKeys = listOf("+", "-", "*", "/", ".")
private val letterPattern = Regex("[a-zA-Z]")

/**
 * 计算器模式按键路由的结果。
 */
sealed class CalculatorRouteResult {
    /** 按键已被计算器路由处理，调用者应跳过后续 Rime 管线 */
    data class Handled(val commitText: String) : CalculatorRouteResult()
    /** 按键不由计算器路由处理，调用者应按正常流程继续 */
    data object NotHandled : CalculatorRouteResult()
}

/**
 * 计算器模式按键路由 — 决定按键应由计算器引擎处理还是直接提交。
 *
 * 返回 [CalculatorRouteResult.Handled] 表示按键已被路由处理，
 * 调用者应提交 [commitText] 并跳过后续 Rime 管线。
 * 返回 [CalculatorRouteResult.NotHandled] 表示调用者应按正常流程继续处理。
 *
 * 路由规则：
 * - CommonSymbol 键盘：符号等非字母的单字符键直接提交（符号无计算器支持）；
 *   **字母键不在此处理**，交回 Rime 管线——实体键盘在符号面板打开时仍应按
 *   当前方案输入（中文态→编码），而不是被当作英文字符直接上屏。
 * - Number 键盘：数字/运算符/小数点先经计算器引擎，再直接提交（不经过 Rime）
 * - 其他键盘：返回 [NotHandled]
 */
fun routeCalculatorKey(
    key: String,
    isNumberKeyboard: Boolean,
    isCommonSymbolKeyboard: Boolean,
    calculatorEngine: CalculatorEngine,
): CalculatorRouteResult {
    if (key.length == 1 && isCommonSymbolKeyboard) {
        if (key.matches(letterPattern)) {
            return CalculatorRouteResult.NotHandled
        }
        return CalculatorRouteResult.Handled(key)
    }

    if (isNumberKeyboard) {
        if (key.matches(calculatorKeyPattern) || key in calculatorKeys) {
            if (key.matches(calculatorKeyPattern) || key == ".") {
                calculatorEngine.handleDigit(key)
            } else {
                calculatorEngine.handleOperator(key)
            }
        }
        return CalculatorRouteResult.Handled(key)
    }

    return CalculatorRouteResult.NotHandled
}
