package com.gamewishingwell.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameRepositoryTest {

    /** readGameFile 对不可用目标（目录/不存在/逃逸/空路径）应抛异常。 */
    private suspend fun assertUnreadable(repo: GameRepository, gameId: Long, path: String) {
        val r = runCatching { repo.readGameFile(gameId, path) }
        assertTrue("应读取失败: $path", r.isFailure)
    }

    private fun repo(dir: File): GameRepository {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(dir)
        return GameRepository(context)
    }

    @Test
    fun `游戏库保存重命名与整体平写`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>", "scenarios.json" to "{\"scenarios\":[]}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>", "scenarios.json" to "{\"scenarios\":[]}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 两区模型：平写覆盖，不产生任何版本簿记（.versions/current.txt/sha256）
            assertEquals("<html>v2</html>", repo.loadGameHtml(meta.id))
            val gameDir = File(dir, "games/${meta.id}")
            assertFalse(File(gameDir, ".versions").exists())
            assertFalse(File(gameDir, "current.txt").exists())
            assertFalse(File(gameDir, "index.html.sha256").exists())
            // 保存按钮整体覆盖：非入口文件也随编辑区落盘
            assertTrue(repo.loadGameFile(meta.id, "scenarios.json")?.contains("scenarios") == true)

            assertTrue(repo.renameGame(meta.id, "新名字"))
            assertEquals("新名字", repo.listGames().first { it.id == meta.id }.title)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存镜像删除-编辑区删掉的文件不再残留保存区`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-mirror-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>", "js/old.js" to "var a=1;", "css/style.css" to "body{}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 编辑区删除了 js/old.js（模型 delete 或整目录重做），保存=整体覆盖：
            // 保存区不在清单内的游戏文件一并移除，目录排空后移除
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>", "css/style.css" to "body{color:red}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")
            assertNull(repo.loadGameFile(meta.id, "js/old.js"))
            assertFalse(File(gameDir, "js").exists())
            assertEquals("body{color:red}", repo.loadGameFile(meta.id, "css/style.css"))
            // 会话簿记豁免（镜像删除不碰）
            assertTrue(File(gameDir, "session.json").exists())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存清理历史版本簿记`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-legacy-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")
            // 模拟旧版本应用遗留的版本簿记
            File(gameDir, ".versions").mkdirs()
            File(gameDir, ".versions/2-index.html").writeText("legacy")
            File(gameDir, "current.txt").writeText("index.html")
            File(gameDir, "index.html.sha256").writeText("deadbeef")
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            assertFalse(File(gameDir, ".versions").exists())
            assertFalse(File(gameDir, "current.txt").exists())
            assertFalse(File(gameDir, "index.html.sha256").exists())
            assertEquals("<html>v2</html>", repo.loadGameHtml(meta.id))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存点快照冻结保存时上下文供撤销恢复`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "m0"))
            )
            // 保存时冻结上下文快照
            repo.writeSavepoint(
                meta.id,
                listOf(ChatMessage("user", "m0"), ChatMessage("assistant", "a0")),
                """{"gameGenerated":true}"""
            )
            assertEquals(2, repo.loadSavepointSession(meta.id)?.size)
            assertEquals("""{"gameGenerated":true}""", repo.loadSavepointAgentState(meta.id))
            // 保存区实时会话继续前进，保存点不受影响（撤销锚点独立于实时副本）
            repo.updateGameSessionOnly(
                meta.id,
                listOf(ChatMessage("user", "m0"), ChatMessage("user", "m1"), ChatMessage("assistant", "a1"))
            )
            assertEquals(2, repo.loadSavepointSession(meta.id)?.size)
            // 不存在的游戏/尚未保存过的游戏返回 null
            assertNull(repo.loadSavepointSession(99999L))
            assertNull(repo.loadSavepointAgentState(99999L))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统列出游戏文件夹一级条目`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )

            val entries = repo.listGameFiles(meta.id)
            val names = entries.map { it.name }
            // 两区模型：平写产出游戏文件与会话簿记，无版本簿记
            assertTrue("index.html" in names)
            assertTrue("session.json" in names)
            assertFalse(".versions" in names)
            val html = entries.first { it.name == "index.html" }
            assertTrue(!html.isDirectory && html.sizeBytes > 0 && html.lastModified > 0)
            // 不存在的游戏返回空
            assertTrue(repo.listGameFiles(99999L).isEmpty())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统进入子目录浏览`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-sub-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 多文件形态产生 js 子目录
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>", "js/main.js" to "var a=2;", "js/ui.js" to "var b=1;"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // js 子目录可进入，看到辅助文件条目
            val sub = repo.listGameFiles(meta.id, "js")
            assertEquals(listOf("main.js", "ui.js"), sub.map { it.name }.sorted())
            assertTrue(sub.all { !it.isDirectory })
            // 不存在的子目录返回空；目标是文件也返回空
            assertTrue(repo.listGameFiles(meta.id, "no-such-dir").isEmpty())
            assertTrue(repo.listGameFiles(meta.id, "index.html").isEmpty())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统只读打开文件`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-open-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 多文件形态写入 js
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>", "js/main.js" to "var a=1;"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // 根目录文件（overwrite 后入口内容为 v2）
            val html = repo.readGameFile(meta.id, "index.html")
            assertEquals("index.html", html.name)
            assertEquals("index.html", html.relativePath)
            assertEquals("<html>v2</html>", html.text)
            assertTrue(!html.binary && !html.truncated)
            // 子目录文件按相对路径读取
            val js = repo.readGameFile(meta.id, "js/main.js")
            assertEquals("js/main.js", js.relativePath)
            assertEquals("var a=1;", js.text)
            // 目录不是文件、不存在路径、不存在的游戏都读取失败
            assertUnreadable(repo, meta.id, "js")
            assertUnreadable(repo, meta.id, "no-such.json")
            assertUnreadable(repo, 99999L, "index.html")
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统拒绝路径逃逸`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-escape-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            // ../ 逃出游戏目录一律拒绝（含多层；目录列表同样拒绝）
            assertUnreadable(repo, meta.id, "../games.json")
            assertUnreadable(repo, meta.id, "../../games.json")
            assertTrue(repo.listGameFiles(meta.id, "..").isEmpty())
            assertTrue(repo.listGameFiles(meta.id, "../../games").isEmpty())
            // 规范化后仍在游戏目录内的路径放行（canonical 解析兜住 ../ 往返）
            assertNotNull(repo.readGameFile(meta.id, "../../games/${meta.id}/index.html"))
            // 空相对路径 = 根目录本身，用于列表
            assertTrue(repo.listGameFiles(meta.id, "").isNotEmpty())
            assertUnreadable(repo, meta.id, "")
        }
        dir.deleteRecursively()
    }

    @Test
    fun `文件可视系统超大文件截断与二进制判定`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-cap-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "打地鼠", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")
            // 超过预览上限：只给前缀，sizeBytes 记真实大小
            val big = "x".repeat(GameRepository.FILE_PREVIEW_LIMIT_BYTES + 100)
            File(gameDir, "big.txt").writeText(big)
            val capped = repo.readGameFile(meta.id, "big.txt")
            assertTrue(capped.truncated)
            assertEquals(big.length.toLong(), capped.sizeBytes)
            assertTrue(capped.text.length < big.length)

            // 含 NUL 字节判为二进制：不做文本预览
            File(gameDir, "blob.bin").writeBytes(byteArrayOf(1, 2, 0, 3))
            val bin = repo.readGameFile(meta.id, "blob.bin")
            assertTrue(bin.binary)
            assertEquals("", bin.text)

            // 空文件可打开
            File(gameDir, "empty.txt").writeText("")
            val empty = repo.readGameFile(meta.id, "empty.txt")
            assertEquals("", empty.text)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存区拒收 versions 路径并清理存量嵌套归档`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-vers-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            // 模拟历史泄漏：files 混入子目录归档路径（旧 currentWorkspaceFiles 过滤缺陷）
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf(
                    "index.html" to "<html>v1</html>",
                    "js/main.js" to "var a=1;",
                    "js/.versions/1-main.js" to "legacy-archive",
                    ".versions/9-index.html" to "root-leak",
                    "current.txt" to "index.html"
                ),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")
            // 游戏文件正常落盘，簿记路径一律拒收（.versions/current.txt 属历史簿记，
            // 传入拒收且存量随保存清理——两区模型不再产生版本簿记）
            assertTrue(File(gameDir, "js/main.js").exists())
            assertFalse(File(gameDir, "js/.versions").exists())
            assertFalse(File(gameDir, ".versions").exists())
            assertFalse(File(gameDir, "current.txt").exists())

            // 存量泄漏修复：预先手工放置嵌套归档，再次保存时被清理
            File(gameDir, "js/.versions").mkdirs()
            File(gameDir, "js/.versions/2-main.js").writeText("stale")
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>", "js/main.js" to "var a=2;"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            assertFalse(File(gameDir, "js/.versions").exists())
            assertEquals("var a=2;", File(gameDir, "js/main.js").readText())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `保存区递归文件清单排除簿记与会话文件`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-paths-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>", "js/main.js" to "var a=1;", "css/style.css" to "body{}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val paths = repo.savedGameFilePaths(meta.id)
            assertEquals(listOf("css/style.css", "index.html", "js/main.js"), paths)
            // 不存在的游戏返回空
            assertTrue(repo.savedGameFilePaths(99999L).isEmpty())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `log目录可视可见但不入游戏文件链路且只随删除顺带清理`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-log-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")
            // Agent 回合日志由 AgentLog 直接写入游戏文件夹 log/ 子目录
            File(gameDir, "log").mkdirs()
            File(gameDir, "log/20260907_115400.log").writeText("turn log")
            // 文件可视系统可见（逐级浏览即达）
            assertTrue(repo.listGameFiles(meta.id).any { it.name == "log" && it.isDirectory })
            assertEquals("turn log", repo.readGameFile(meta.id, "log/20260907_115400.log").text)
            // 撤销清单与运行合并素材一律排除 log/（日志不是游戏文件）
            assertFalse(repo.savedGameFilePaths(meta.id).any { it.startsWith("log/") })
            assertFalse(repo.loadGameTextFiles(meta.id).containsKey("log/20260907_115400.log"))
            // 「导出文件」读取完整字节
            assertEquals("turn log", String(repo.readGameFileBytes(meta.id, "log/20260907_115400.log")!!))
            // 保存不触碰日志；删除游戏时顺带删除（无独立清理路径）
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            assertTrue(File(gameDir, "log/20260907_115400.log").exists())
            repo.deleteGame(meta.id)
            assertFalse(File(gameDir, "log").exists())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `草稿入库时日志迁移进游戏文件夹`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-adopt-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            File(File(dir, "drafts"), "log").mkdirs()
            File(File(dir, "drafts/log"), "20260907_115400.log").writeText("turn1")
            File(File(dir, "drafts/log"), "20260907_115500.log").writeText("turn2")
            repo.adoptDraftLogs(meta.id)
            // 迁移后：游戏文件夹可见全部回合日志（文件夹可视系统即达），草稿 log 目录消失
            val names = File(File(dir, "games/${meta.id}"), "log").listFiles().orEmpty()
                .map { it.name }.sorted()
            assertEquals(listOf("20260907_115400.log", "20260907_115500.log"), names)
            assertEquals("turn1", File(File(dir, "games/${meta.id}/log"), "20260907_115400.log").readText())
            assertFalse(File(File(dir, "drafts"), "log").exists())
            // 空/不存在草稿日志时幂等无害
            repo.adoptDraftLogs(meta.id)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `草稿文件夹可视浏览与清理`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-draft-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            repo.saveDraft("<html>d1</html>", listOf(ChatMessage("user", "做一个游戏")))
            repo.saveDraftAgentState("""{"gameGenerated":false}""")
            File(File(dir, "drafts"), "log").mkdirs()
            File(File(dir, "drafts/log"), "20260907_115400.log").writeText("draft turn log")

            // DRAFT_ID 键位浏览/读取草稿文件夹
            val names = repo.listGameFiles(GameRepository.DRAFT_ID).map { it.name }
            assertTrue("latest.html" in names)
            assertTrue("log" in names)
            assertEquals(
                "draft turn log",
                repo.readGameFile(GameRepository.DRAFT_ID, "log/20260907_115400.log").text
            )
            // 导出源数据同样可用；逃逸校验以 drafts 为根
            assertEquals("draft turn log", String(repo.readGameFileBytes(GameRepository.DRAFT_ID, "log/20260907_115400.log")!!))
            assertUnreadable(repo, GameRepository.DRAFT_ID, "../games.json")
            // clearDraft 顺带删除草稿日志
            repo.clearDraft()
            assertTrue(repo.listGameFiles(GameRepository.DRAFT_ID).isEmpty())
            assertFalse(File(File(dir, "drafts"), "log").exists())
        }
        dir.deleteRecursively()
    }

    /** 手工构造 zip（字节数组）：定制条目名/内容，验证导入侧的容错与防御。 */
    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `游戏包导出导入往返`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zip-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "跨设备描述",
                mapOf("index.html" to "<html>v1</html>", "js/main.js" to "var a=1;", "css/style.css" to "body{}"),
                listOf(ChatMessage("user", "m0"))
            )
            repo.saveGameAgentState(meta.id, """{"gameGenerated":true}""")
            repo.writeSavepoint(meta.id, listOf(ChatMessage("user", "m0")), """{"gameGenerated":true}""")
            val gameDir = File(dir, "games/${meta.id}")
            File(gameDir, "log").mkdirs()
            File(gameDir, "log/20260907_115400.log").writeText("turn log")

            val zip = ByteArrayOutputStream()
            assertTrue(repo.exportGameZip(meta.id, zip))
            val imported = repo.importGameZip(ByteArrayInputStream(zip.toByteArray()))
            assertNotNull(imported)
            // 新 id = 全新游戏身份（可共存），元数据凭包内清单还原
            assertTrue(imported!!.id != meta.id)
            assertEquals("塔防", imported.title)
            assertEquals("跨设备描述", imported.description)
            assertTrue(repo.listGames().any { it.id == imported.id })
            // 游戏文件/会话簿记/保存点/日志全量往返
            val newDir = File(dir, "games/${imported.id}")
            assertEquals("<html>v1</html>", File(newDir, "index.html").readText())
            assertEquals("var a=1;", File(newDir, "js/main.js").readText())
            assertEquals("body{}", File(newDir, "css/style.css").readText())
            assertTrue(File(newDir, "session.json").exists())
            assertTrue(File(newDir, "agent_state.json").exists())
            assertEquals("turn log", File(newDir, "log/20260907_115400.log").readText())
            assertEquals("""{"gameGenerated":true}""", repo.loadSavepointAgentState(imported.id))
            // 清单只读消费，不落进游戏文件夹（不混入游戏文件链路）
            assertFalse(File(newDir, GameRepository.PACKAGE_MANIFEST).exists())
            // 不存在的游戏导出失败
            assertFalse(repo.exportGameZip(99999L, ByteArrayOutputStream()))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导入剥外部打包工具的单一顶层目录前缀`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zippfx-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            // "zip -r out.zip 123/" 形态：全部条目共享顶层目录 "123/"
            val zip = zipOf(
                "123/index.html" to "<html>ext</html>",
                "123/js/main.js" to "var a=1;"
            )
            val imported = repo.importGameZip(ByteArrayInputStream(zip))
            assertNotNull(imported)
            // 无清单（外部手打 zip）：标题回退通用名
            assertEquals("导入的游戏", imported!!.title)
            assertEquals("<html>ext</html>", repo.loadGameHtml(imported.id))
            assertEquals("var a=1;", repo.loadGameFile(imported.id, "js/main.js"))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导入拒绝非法游戏包`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zipbad-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            // 缺 index.html：普通 zip 不是游戏包
            assertNull(repo.importGameZip(ByteArrayInputStream(zipOf("readme.txt" to "hi"))))
            // 路径逃逸：../ 条目一律判包非法
            assertNull(
                repo.importGameZip(
                    ByteArrayInputStream(zipOf("index.html" to "<html/>", "../evil.txt" to "x"))
                )
            )
            // 绝对路径条目同样非法
            assertNull(
                repo.importGameZip(
                    ByteArrayInputStream(zipOf("/etc/evil" to "x"))
                )
            )
            // 空 zip 非法
            assertNull(repo.importGameZip(ByteArrayInputStream(zipOf())))
            // 全部失败：索引无新增
            assertTrue(repo.listGames().isEmpty())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导入过滤旧版版本簿记`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-ziplegacy-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val zip = zipOf(
                "index.html" to "<html>v1</html>",
                ".versions/9-index.html" to "legacy",
                "current.txt" to "index.html",
                "index.html.sha256" to "deadbeef"
            )
            val imported = repo.importGameZip(ByteArrayInputStream(zip))
            assertNotNull(imported)
            val gameDir = File(dir, "games/${imported!!.id}")
            assertFalse(File(gameDir, ".versions").exists())
            assertFalse(File(gameDir, "current.txt").exists())
            assertFalse(File(gameDir, "index.html.sha256").exists())
            assertEquals("<html>v1</html>", File(gameDir, "index.html").readText())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导出包含编辑区未保存修改且导入落位复原`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zipws-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>saved-v1</html>", "js/main.js" to "var a=1;"),
                listOf(ChatMessage("user", "做一个塔防"))
            )
            repo.saveGameAgentState(meta.id, """{"gameGenerated":true,"unsavedVersion":true}""")
            // 编辑区存在未保存的修改（比保存区新）：入口更新 + 新增辅助文件
            val wsDir = File(dir, "agent/workspace/game-${meta.id}")
            File(wsDir, "js").mkdirs()
            File(wsDir, "index.html").writeText("<html>editing-v2</html>")
            File(wsDir, "js/jump.js").writeText("function jump(){}")

            val zip = ByteArrayOutputStream()
            assertTrue(repo.exportGameZip(meta.id, zip))
            val imported = repo.importGameZip(ByteArrayInputStream(zip.toByteArray()))
            assertNotNull(imported)
            // 保存区原样复原（仍是入库版本）
            assertEquals("<html>saved-v1</html>", repo.loadGameHtml(imported!!.id))
            assertEquals("var a=1;", repo.loadGameFile(imported.id, "js/main.js"))
            // 编辑区落位到新会话的工作区：未保存的修改随包复原（会话描述与文件一致，
            // unsavedVersion=true 的现场不再撕裂）
            val newWs = File(dir, "agent/workspace/game-${imported.id}")
            assertEquals("<html>editing-v2</html>", File(newWs, "index.html").readText())
            assertEquals("function jump(){}", File(newWs, "js/jump.js").readText())
            // 未保存标记随 agent_state 往返：首页"!"语义在导入侧成立
            assertTrue(repo.loadGameAgentState(imported.id)!!.contains("\"unsavedVersion\""))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导入初始化缺失的会话簿记与保存点`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zipinit-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            // 外部手打 zip：只有游戏入口，无任何会话簿记
            val zip = zipOf("index.html" to "<html>v1</html>")
            val imported = repo.importGameZip(ByteArrayInputStream(zip))
            assertNotNull(imported)
            val gameDir = File(dir, "games/${imported!!.id}")
            // 按首轮对话环境初始化：空消息列表 + 默认会话状态（GameSession 全字段默认值）
            assertEquals("[]", File(gameDir, "session.json").readText())
            assertEquals("{}", File(gameDir, "agent_state.json").readText())
            // 保存点同步合成（导入=入库语义）：撤销锚点可用，不走"旧版本无保存点"清空对话路径
            assertEquals("[]", File(gameDir, ".savepoint/session.json").readText())
            assertEquals("{}", File(gameDir, ".savepoint/agent_state.json").readText())
            // 初始化结果与装载链路自洽：空对话 + 默认状态可正常读回
            assertTrue(repo.loadGameSession(imported.id).isEmpty())
            assertNotNull(repo.loadGameAgentState(imported.id))
            assertEquals("[]", repo.loadSavepointSession(imported.id) ?: "[]")
            assertNotNull(repo.loadSavepointAgentState(imported.id))
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导入检测损坏簿记并按缺失初始化`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zipcorrupt-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            // 会话簿记损坏（导出撕裂写入/外部手改形态）：session 非法 JSON、
            // agent_state 是数组（须为对象）、保存点成员同污
            val zip = zipOf(
                "index.html" to "<html>v1</html>",
                "session.json" to "{\"oops\"",
                "agent_state.json" to "[1,2]",
                ".savepoint/session.json" to "not json at all",
                ".savepoint/agent_state.json" to "<<<"
            )
            val imported = repo.importGameZip(ByteArrayInputStream(zip))
            assertNotNull(imported)
            val gameDir = File(dir, "games/${imported!!.id}")
            // 损坏一律按缺失处理并初始化，不静默降级、不拒整包
            assertEquals("[]", File(gameDir, "session.json").readText())
            assertEquals("{}", File(gameDir, "agent_state.json").readText())
            assertEquals("[]", File(gameDir, ".savepoint/session.json").readText())
            assertEquals("{}", File(gameDir, ".savepoint/agent_state.json").readText())
            assertEquals("<html>v1</html>", File(gameDir, "index.html").readText())
        }
        dir.deleteRecursively()
    }

    @Test
    fun `导入为缺失保存点合成导入内容锚点`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-zipsp-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            // 旧版本应用导出的包（无 .savepoint）：会话与状态完好
            val messages = """[{"role":"user","content":"做一个塔防"},{"role":"assistant","content":"已交付"}]"""
            val zip = zipOf(
                "index.html" to "<html>v1</html>",
                "session.json" to messages,
                "agent_state.json" to """{"gameGenerated":true,"qualityTier":"premium"}"""
            )
            val imported = repo.importGameZip(ByteArrayInputStream(zip))
            assertNotNull(imported)
            val gameDir = File(dir, "games/${imported!!.id}")
            // 保存点从导入内容合成：撤销锚点=入库时刻现场（消息与状态与导入内容一致）
            assertEquals(messages, File(gameDir, ".savepoint/session.json").readText())
            assertEquals(
                """{"gameGenerated":true,"qualityTier":"premium"}""",
                File(gameDir, ".savepoint/agent_state.json").readText()
            )
            assertEquals(2, repo.loadSavepointSession(imported.id)?.size)
        }
        dir.deleteRecursively()
    }

    @Test
    fun `玩家参数覆盖-读写校验与两区链路豁免`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "repo-ucfg-${System.nanoTime()}")
        val repo = repo(dir)
        runBlocking {
            val meta = repo.saveGame(
                "塔防", "描述",
                mapOf("index.html" to "<html>v1</html>", "js/main.js" to "var a=1;"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            val gameDir = File(dir, "games/${meta.id}")

            // 写入：只收 JSON 对象；数组/坏 JSON 拒绝不落盘
            assertTrue(repo.writeUserConfig(meta.id, """{"playerSpeed": 260}"""))
            assertFalse(repo.writeUserConfig(meta.id, "[1,2]"))
            assertFalse(repo.writeUserConfig(meta.id, "{oops"))
            assertEquals("""{"playerSpeed": 260}""", repo.readUserConfig(meta.id))
            // 不存在的游戏拒写；不存在的文件读 null
            assertFalse(repo.writeUserConfig(99999L, "{}"))
            assertNull(repo.readUserConfig(99999L))

            // 保存（编辑区整体覆盖+镜像删除）不碰玩家覆盖：它不是游戏文件
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v2</html>"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            assertEquals("""{"playerSpeed": 260}""", repo.readUserConfig(meta.id))
            // 撤销清单与运行合并素材排除（不进编辑区、不被 GameBundle 内联）
            assertFalse(repo.savedGameFilePaths(meta.id).contains(GameRepository.USER_CONFIG_FILE))
            assertFalse(repo.loadGameTextFiles(meta.id).containsKey(GameRepository.USER_CONFIG_FILE))

            // 模型侧同名"游戏文件"拒收（平台通道防冒用）：保存清单里的同名文件不落盘
            repo.overwriteGameFiles(
                meta.id,
                mapOf("index.html" to "<html>v3</html>", GameRepository.USER_CONFIG_FILE to "{\"hacked\":true}"),
                listOf(ChatMessage("user", "做一个游戏"))
            )
            assertEquals("""{"playerSpeed": 260}""", repo.readUserConfig(meta.id))

            // 清除：恢复默认（幂等）
            repo.clearUserConfig(meta.id)
            assertNull(repo.readUserConfig(meta.id))
            repo.clearUserConfig(meta.id)

            // 导出/导入随保存区整体往返：玩家调参随包迁移
            repo.writeUserConfig(meta.id, """{"speed": 9}""")
            val zip = ByteArrayOutputStream()
            assertTrue(repo.exportGameZip(meta.id, zip))
            val imported = repo.importGameZip(ByteArrayInputStream(zip.toByteArray()))
            assertEquals("""{"speed": 9}""", repo.readUserConfig(imported!!.id))
        }
        dir.deleteRecursively()
    }
}
