package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [WubiEnhanceManager] managed-block patch surgery on
 * `<schema>.custom.yaml`（四码上屏 / Z键反查 / 长句连打）。
 */
class WubiEnhanceManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun rimeDir(): File = tempDir.newFolder("rime")

    /** 安装方案（schema 文件在场才算 installed）。 */
    private fun install(dir: File, vararg schemaIds: String) {
        for (id in schemaIds) {
            File(dir, "$id.schema.yaml").writeText("schema:\n  schema_id: $id\n")
        }
    }

    private fun reconcile(
        dir: File,
        installed: Set<String>,
        autoSelect: Boolean = true,
        zReverse: Boolean = false,
        longSentence: Boolean = false,
    ) = WubiEnhanceManager.reconcile(
        rimeDir = dir,
        installed = installed,
        autoSelect = autoSelect,
        zReverse = zReverse,
        longSentence = longSentence,
    )

    @Test
    fun `auto select on writes four code keys`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        val result = reconcile(dir, setOf("wubi86"))
        val text = File(dir, "wubi86.custom.yaml").readText()
        assertTrue(text.contains(WubiEnhanceManager.BEGIN))
        assertTrue(text.contains("\"speller/auto_select\": true"))
        assertTrue(text.contains("\"speller/max_code_length\": 4"))
        assertTrue("wubi86 在适用列表", "wubi86" in result.appliedSchemas)
    }

    @Test
    fun `auto select off drops max_code_length`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        reconcile(dir, setOf("wubi86"), autoSelect = false)
        val text = File(dir, "wubi86.custom.yaml").readText()
        assertTrue(text.contains("\"speller/auto_select\": false"))
        assertFalse(text.contains("max_code_length"))
    }

    @Test
    fun `z reverse only appends translator for pure wubi`() {
        val dir = rimeDir()
        install(dir, "wubi86", "wubi86_pinyin")
        reconcile(dir, setOf("wubi86", "wubi86_pinyin"), zReverse = true)
        val pure = File(dir, "wubi86.custom.yaml").readText()
        val mixed = File(dir, "wubi86_pinyin.custom.yaml").readText()
        // 纯五笔：追加翻译器 + 识别模式 + 反查配置
        assertTrue(pure.contains("- reverse_lookup_translator"))
        assertTrue(pure.contains("\"recognizer/patterns/reverse_lookup\": \"^z([a-z]+)'?$\""))
        assertTrue(pure.contains("dictionary: pinyin_simp"))
        // 混输自带反查：不追加翻译器
        assertFalse(mixed.contains("reverse_lookup_translator"))
        assertFalse(mixed.contains("recognizer/patterns/reverse_lookup"))
    }

    @Test
    fun `long sentence writes enable_sentence and phrase length`() {
        val dir = rimeDir()
        install(dir, "wubi98")
        reconcile(dir, setOf("wubi98"), longSentence = true)
        val text = File(dir, "wubi98.custom.yaml").readText()
        assertTrue(text.contains("\"translator/enable_sentence\": true"))
        assertTrue(text.contains("\"translator/max_phrase_length\": 10"))
    }

    @Test
    fun `personal dict append is absorbed into single translators key`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        val custom = File(dir, "wubi86.custom.yaml")
        // 模拟 PersonalDictManager.insertUnderPatch 写入的个人词库补丁
        custom.writeText(
            """
            patch:
              "engine/translators/+":
                - table_translator@custom_phrase
              "custom_phrase":
                dictionary: ""
                user_dict: custom_phrase
                db_class: stabledb
            """.trimIndent() + "\n",
        )
        reconcile(dir, setOf("wubi86"), zReverse = true)
        val text = custom.readText()
        // 两个追加项合并进托管块同一列表
        assertTrue(text.contains("- reverse_lookup_translator"))
        assertTrue(text.contains("- table_translator@custom_phrase"))
        // 全文只允许一个 translators/+ 键（YAML 重复键会后者覆盖前者）
        assertEquals(
            1,
            Regex("\"engine/translators/\\+\"").findAll(text).count(),
        )
        // 个人词库的 custom_phrase 配置节点保留
        assertTrue(text.contains("\"custom_phrase\":"))
    }

    @Test
    fun `user patch conflict skips schema`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        val custom = File(dir, "wubi86.custom.yaml")
        custom.writeText("patch:\n  \"speller/auto_select\": false\n")
        val result = reconcile(dir, setOf("wubi86"))
        assertTrue("wubi86 应跳过", "wubi86" in result.skippedSchemas)
        // 用户手写键原样保留
        val text = custom.readText()
        assertTrue(text.contains("\"speller/auto_select\": false"))
        assertFalse(text.contains(WubiEnhanceManager.BEGIN))
    }

    @Test
    fun `uninstalled schema leftover block is stripped`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        reconcile(dir, setOf("wubi86"))
        assertTrue(File(dir, "wubi86.custom.yaml").isFile)
        // 卸载（schema 文件不在）后对账：托管块摘除，只剩空 patch 头则删除文件
        File(dir, "wubi86.schema.yaml").delete()
        reconcile(dir, emptySet())
        // 文件只有我们的内容 → 整个删除
        assertFalse(File(dir, "wubi86.custom.yaml").exists())
    }

    @Test
    fun `uninstalled schema with user patch keeps user content`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        val custom = File(dir, "wubi86.custom.yaml")
        // 先写托管块，再混入用户自己的 patch 键
        reconcile(dir, setOf("wubi86"))
        custom.appendText("  \"menu/page_size\": 9\n")
        File(dir, "wubi86.schema.yaml").delete()
        reconcile(dir, emptySet())
        val text = custom.readText()
        assertFalse("托管块应摘除", text.contains(WubiEnhanceManager.BEGIN))
        assertTrue("用户键保留", text.contains("\"menu/page_size\": 9"))
    }

    @Test
    fun `re reconcile is idempotent`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        reconcile(dir, setOf("wubi86"), zReverse = true, longSentence = true)
        val first = File(dir, "wubi86.custom.yaml").readText()
        val result = reconcile(dir, setOf("wubi86"), zReverse = true, longSentence = true)
        val second = File(dir, "wubi86.custom.yaml").readText()
        assertEquals("重复对账内容不变", first, second)
        assertFalse("无变化不算 changed", "wubi86" in result.changedSchemas)
    }

    @Test
    fun `absorbed personal dict entry survives re reconcile`() {
        val dir = rimeDir()
        install(dir, "wubi86")
        val custom = File(dir, "wubi86.custom.yaml")
        custom.writeText(
            """
            patch:
              "engine/translators/+":
                - table_translator@custom_phrase
              "custom_phrase":
                dictionary: ""
                user_dict: custom_phrase
            """.trimIndent() + "\n",
        )
        reconcile(dir, setOf("wubi86"), zReverse = true)
        reconcile(dir, setOf("wubi86"), zReverse = true)
        val text = custom.readText()
        // 二次对账后吸纳项仍在，且 translators/+ 键仍唯一
        assertTrue(text.contains("- table_translator@custom_phrase"))
        assertEquals(
            1,
            Regex("\"engine/translators/\\+\"").findAll(text).count(),
        )
    }
}
