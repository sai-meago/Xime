package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把用户级五笔增强设置（四码直接上屏 / Z键拼音反查 / 长句连打）物化成
 * 五笔方案的 `<schema>.custom.yaml` 托管块补丁。
 *
 * 与 [FuzzyPinyinManager] 同一套模式：状态只存 prefs；custom 文件是派生物，
 * 换方案集时随方案内容删除，下次启动/部署由 [reconcile] 按 prefs 重放。
 *
 * 与个人词库补丁的共存（[PersonalDictManager] 同样写 `"engine/translators/+"`）：
 * YAML 重复键后者覆盖前者，两处各写一份会互相吞掉。对策：发现个人词库的
 * 追加项时并入本托管块同一列表并摘除旧条目——PersonalDictManager 的幂等检查
 * （contains "table_translator@custom_phrase"）会因此跳过自身插入，
 * 任何时刻文件里最多只有一个 `"engine/translators/+"` 键。
 *
 * Z 键反查只作用于纯五笔方案（wubi86 / wubi98，字母表含 z 且词典无 z 编码，
 * z 引导拼音不会被五笔编码冲突）；wubi86_pinyin 全量混输、wubi86_trad 系
 * 自带反查，无需补丁。
 */
object WubiEnhanceManager {

    private const val TAG = "WubiEnhance"

    const val BEGIN = "# >>> xime:wubi-enhance (managed)"
    const val END = "# <<< xime:wubi-enhance (managed)"

    /** 受托管的内置五笔方案白名单（市场第三方五笔方案不受托管，尊重作者调优）。 */
    val MANAGED_SCHEMAS = listOf(
        "wubi86", "wubi98", "wubi86_pinyin", "wubi86_trad", "wubi86_trad_pinyin",
    )

    /** 纯五笔方案：无拼音反查能力，Z 键反查需追加翻译器与识别模式。 */
    private val PURE_WUBI = setOf("wubi86", "wubi98")

    /** 用户手写过这些键则跳过该方案（同模糊拼音的 USER_PATCH_CONFLICT 策略）。 */
    private val conflictKeys = listOf(
        """^[ \t]*["']?speller/auto_select["']?[ \t]*:""",
        """^[ \t]*["']?speller/max_code_length["']?[ \t]*:""",
        """^[ \t]*["']?translator/enable_sentence["']?[ \t]*:""",
        """^[ \t]*["']?recognizer/patterns/reverse_lookup["']?[ \t]*:""",
    ).map { it.toRegex(RegexOption.MULTILINE) }

    /** 任何非个人词库格式的 translators 追加键（用户手写）都视为冲突。 */
    private val anyTranslatorsAppend =
        Regex("""(?m)^[ \t]*["']?engine/translators/\+["']?[ \t]*:""")

    /** 个人词库补丁写入的两行追加项（发现即并入托管块并摘除原条目）。
     *  结尾用 (?:\n|$)：基线经过 trimEnd 后最后一行无换行符也能命中。 */
    private val personalDictAppend = Regex(
        """(?m)^  "engine/translators/\+"[ \t]*:[ \t]*\n[ \t]*- table_translator@custom_phrase(?:\n|$)"""
    )

    private val patchHeader = Regex("""(?m)^patch[ \t]*:[ \t]*(?:\{\s*\})?[ \t]*$""")
    private val anyPatchHeader = Regex("""(?m)^patch[ \t]*:""")

    data class ReconcileResult(
        val changedSchemas: List<String>,
        val appliedSchemas: List<String>,
        val skippedSchemas: List<String>,
    )

    /** 适用方案（设置页展示用）：已安装的五笔方案。 */
    fun applicableSchemas(context: Context): List<String> {
        val rimeDir = SchemeFiles.rimeDir(context)
        val installed = SchemeFiles.installedSchemaIds(rimeDir).toSet()
        return MANAGED_SCHEMAS.filter { it in installed }
    }

    /** 对账：把当前 prefs 状态写进各五笔方案托管块；未安装方案的残留块摘除。 */
    fun reconcile(context: Context): ReconcileResult {
        val rimeDir = SchemeFiles.rimeDir(context)
        return reconcile(
            rimeDir = rimeDir,
            installed = SchemeFiles.installedSchemaIds(rimeDir),
            autoSelect = SettingsPreferences.isWubiAutoSelect(context),
            zReverse = SettingsPreferences.isWubiZReverse(context),
            longSentence = SettingsPreferences.isWubiLongSentence(context),
        )
    }

    /** 纯文件对账（可单测）：不依赖 Android Context / prefs。 */
    internal fun reconcile(
        rimeDir: File,
        installed: Collection<String>,
        autoSelect: Boolean,
        zReverse: Boolean,
        longSentence: Boolean,
    ): ReconcileResult {
        val installedSet = installed.toSet()
        val changed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val applied = mutableListOf<String>()

        for (schemaId in MANAGED_SCHEMAS) {
            val file = File(rimeDir, "$schemaId.custom.yaml")
            val managed = schemaId in installedSet
            val write = updateCustomFile(file, schemaId, if (managed) {
                WriteSpec(autoSelect, zReverse, longSentence)
            } else {
                null
            })
            when {
                write.error || write.conflict -> skipped += schemaId
                write.changed -> changed += schemaId
            }
            if (managed && !write.error && !write.conflict) applied += schemaId
        }

        if (skipped.isNotEmpty()) {
            FileLogger.w(TAG, "wubi reconcile skipped: ${skipped.joinToString()}")
        }
        return ReconcileResult(
            changedSchemas = changed,
            appliedSchemas = applied,
            skippedSchemas = skipped,
        )
    }

    internal data class WriteSpec(val autoSelect: Boolean, val zReverse: Boolean, val longSentence: Boolean)

    internal data class WriteResult(
        val changed: Boolean,
        val conflict: Boolean,
        val error: Boolean,
    )

    private fun updateCustomFile(
        file: File,
        schemaId: String,
        spec: WriteSpec?,
    ): WriteResult {
        val exists = file.isFile
        val original = if (exists) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                FileLogger.w(TAG, "read ${file.name} failed", it)
                return WriteResult(changed = false, conflict = false, error = true)
            }
        } else {
            ""
        }

        // 摘掉旧托管块得到基线（用户/其他功能的 patch 原样保留）
        val base = strip(stripDocumentEnd(original.removePrefix("\uFEFF")).trimEnd())

        // 未托管（方案已卸载）且无我们的块：不碰用户文件
        if (spec == null && !original.contains(BEGIN)) {
            return WriteResult(changed = false, conflict = false, error = false)
        }

        if (spec == null) {
            // 卸载清理：摘块后只剩我们建的空 patch 头则整个删除
            val cleaned = cleanupEmptyPatch(base)
            return try {
                if (cleaned.isBlank()) {
                    if (exists) file.delete()
                } else {
                    writeAtomically(file, "$cleaned\n")
                }
                WriteResult(changed = cleaned != original, conflict = false, error = false)
            } catch (e: Exception) {
                FileLogger.w(TAG, "write ${file.name} failed", e)
                WriteResult(changed = false, conflict = false, error = true)
            }
        }

        // 用户手写过同类键：不硬合并，跳过并提示（同模糊拼音策略）
        if (conflictKeys.any { it.containsMatchIn(base) }) {
            val stripped = strip(original)
            return try {
                if (stripped != original) writeAtomically(file, stripped)
                WriteResult(changed = stripped != original, conflict = true, error = false)
            } catch (e: Exception) {
                FileLogger.w(TAG, "write ${file.name} failed", e)
                WriteResult(changed = false, conflict = true, error = true)
            }
        }

        // 个人词库追加项：并入托管块列表并从基线摘除，保证 translators/+ 键唯一。
        // 检测必须看全文（含旧托管块）：一旦吸纳过就持续吸纳，否则第二次对账会
        // 丢失该项、PersonalDictManager 幂等检查失效导致重复键回归。
        val absorbCustomPhrase = original.contains("- table_translator@custom_phrase") ||
                personalDictAppend.containsMatchIn(base)
        var working = if (absorbCustomPhrase) {
            personalDictAppend.replace(base, "")
        } else {
            base
        }
        // 用户手写的其他 translators 追加（非个人词库格式）：Z 反查无法安全共存 → 冲突
        if (anyTranslatorsAppend.containsMatchIn(working)) {
            return WriteResult(changed = false, conflict = true, error = false)
        }

        val block = buildBlock(schemaId, spec, absorbCustomPhrase)
        val content = when {
            working.isBlank() -> "patch:\n$block"
            patchHeader.containsMatchIn(working) -> {
                val match = patchHeader.find(working)!!
                (working.substring(0, match.range.first) +
                    "patch:\n$block" +
                    working.substring(match.range.last + 1)).trimEnd() + "\n"
            }
            anyPatchHeader.containsMatchIn(working) ->
                // `patch: {foo: bar}` 行内 map 不冒险改写，跳过
                return WriteResult(changed = false, conflict = true, error = false)
            else -> "$working\n\npatch:\n$block"
        }
        return try {
            writeAtomically(file, content)
            WriteResult(changed = content != original, conflict = false, error = false)
        } catch (e: Exception) {
            FileLogger.w(TAG, "write ${file.name} failed", e)
            WriteResult(changed = false, conflict = false, error = true)
        }
    }

    private fun buildBlock(schemaId: String, spec: WriteSpec, absorbCustomPhrase: Boolean): String =
        buildString {
            append(BEGIN).append('\n')
            // 长句连打需连续编码组句；四码自动上屏/定长会掐断组句，二者互斥——
            // 开启长句时强制关闭四码上屏（并去掉 max_code_length 上限）。
            val fourCode = spec.autoSelect && !spec.longSentence
            if (fourCode) {
                append("  \"speller/auto_select\": true\n")
                append("  \"speller/auto_select_unique_candidate\": true\n")
                append("  \"speller/max_code_length\": 4\n")
            } else {
                append("  \"speller/auto_select\": false\n")
                append("  \"speller/auto_select_unique_candidate\": false\n")
            }
            if (spec.longSentence) {
                append("  \"translator/enable_sentence\": true\n")
                append("  \"translator/max_phrase_length\": 20\n")
            } else {
                append("  \"translator/enable_sentence\": false\n")
            }
            val needReverseAppend = spec.zReverse && schemaId in PURE_WUBI
            if (needReverseAppend || absorbCustomPhrase) {
                append("  \"engine/translators/+\":\n")
                if (needReverseAppend) append("    - reverse_lookup_translator\n")
                if (absorbCustomPhrase) append("    - table_translator@custom_phrase\n")
            }
            if (needReverseAppend) {
                append("  \"recognizer/patterns/reverse_lookup\": \"^z([a-z]+)'?$\"\n")
                append("  \"reverse_lookup\":\n")
                append("    dictionary: pinyin_simp\n")
                append("    prefix: \"z\"\n")
                append("    suffix: \"'\"\n")
                append("    tips: \"〔拼音〕\"\n")
            }
            append(END).append('\n')
        }

    /** 摘掉托管块；没有标记时原样返回。 */
    internal fun strip(text: String): String {
        if (!text.contains(BEGIN)) return text
        val out = ArrayList<String>()
        var inside = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed == BEGIN) {
                inside = true
                continue
            }
            if (inside) {
                if (trimmed == END) inside = false
                continue
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    /** 只剩我们创建的空 `patch:` 头时返回空串，调用方可删除文件。 */
    private fun cleanupEmptyPatch(base: String): String {
        val trimmed = base.trim()
        return if (trimmed.isEmpty() || trimmed == "patch:" || trimmed == "patch: {}") "" else base
    }

    private fun stripDocumentEnd(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith("...")) trimmed.dropLast(3).trimEnd() else trimmed
    }

    private fun writeAtomically(file: File, content: String) {
        val dir = file.parentFile ?: error("no parent dir: ${file.path}")
        dir.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
