/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.jvm.repl

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import junit.framework.TestCase
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.common.repl.*
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.integration.KotlinIntegrationTestBase
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import org.jetbrains.kotlin.script.KotlinScriptDefinitionFromAnnotatedTemplate
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestJdkKind
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

class GenericReplTest : TestCase() {
    @Test
    fun testReplBasics() {

        val disposable = Disposer.newDisposable()

        val repl = TestRepl(disposable,
                            listOf(File(KotlinIntegrationTestBase.getCompilerLib(), "kotlin-runtime.jar")),
                            "kotlin.script.templates.standard.ScriptTemplateWithArgs")

        val res1 = repl.replCompiler?.check(ReplCodeLine(0, "val x ="))
        TestCase.assertTrue("Unexpected check results: $res1", res1 is ReplCheckResult.Incomplete)

        val codeLine0 = ReplCodeLine(0, "val l1 = listOf(1 + 2)\nl1.first()")
        val res2 = repl.replCompiler?.compile(codeLine0, emptyList())
        val res2c = res2 as? ReplCompileResult.CompiledClasses
        TestCase.assertNotNull("Unexpected compile result: $res2", res2c)

        val res21 = repl.compiledEvaluator.eval(res2c!!)
        val res21e = res21 as? ReplEvalResult.ValueResult
        TestCase.assertNotNull("Unexpected eval result: $res21", res21e)
        TestCase.assertEquals(3, res21e!!.value)

        val codeLine1 = ReplCodeLine(1, "val x = 5")
        val res3 = repl.replCompiler?.compile(codeLine1, listOf(codeLine0))
        val res3c = res3 as? ReplCompileResult.CompiledClasses
        TestCase.assertNotNull("Unexpected compile result: $res3", res3c)

        val res31 = repl.compiledEvaluator.eval(res3c!!)
        val res31e = res31 as? ReplEvalResult.UnitResult
        TestCase.assertNotNull("Unexpected eval result: $res31", res31e)

        val codeLine2 = ReplCodeLine(2, "x + 2")
        val res4x = repl.replCompiler?.compile(codeLine2, listOf(codeLine1))
        TestCase.assertNotNull("Unexpected compile result: $res4x", res4x as? ReplCompileResult.HistoryMismatch)

        val res4 = repl.replCompiler?.compile(codeLine2, listOf(codeLine0, codeLine1))
        val res4c = res4 as? ReplCompileResult.CompiledClasses
        TestCase.assertNotNull("Unexpected compile result: $res4", res4c)

        val res41 = repl.compiledEvaluator.eval(res4c!!)
        val res41e = res41 as? ReplEvalResult.ValueResult
        TestCase.assertNotNull("Unexpected eval result: $res41", res41e)
        TestCase.assertEquals(7, res41e!!.value)

        Disposer.dispose(disposable)
    }

    @Test
    fun testReplCodeFormat() {

        val disposable = Disposer.newDisposable()

        val repl = TestRepl(disposable,
                            listOf(File(KotlinIntegrationTestBase.getCompilerLib(), "kotlin-runtime.jar")),
                            "kotlin.script.templates.standard.ScriptTemplateWithArgs")

        val codeLine0 = ReplCodeLine(0, "val l1 = 1\r\nl1\r\n")
        val res0 = repl.replCompiler?.check(codeLine0)
        val res0c = res0 as? ReplCheckResult.Ok
        TestCase.assertNotNull("Unexpected compile result: $res0", res0c)

        Disposer.dispose(disposable)
    }

    @Test
    fun testRepPackage() {

        val disposable = Disposer.newDisposable()

        val repl = TestRepl(disposable,
                            listOf(File(KotlinIntegrationTestBase.getCompilerLib(), "kotlin-runtime.jar")),
                            "kotlin.script.templates.standard.ScriptTemplateWithArgs")

        val codeLine1 = ReplCodeLine(0, "package mypackage\n\nval x = 1\nx+2")
        val res1 = repl.replCompiler?.compile(codeLine1, emptyList())
        val res1c = res1 as? ReplCompileResult.CompiledClasses
        TestCase.assertNotNull("Unexpected compile result: $res1", res1c)

        val res11 = repl.compiledEvaluator.eval(res1c!!)
        val res11e = res11 as? ReplEvalResult.ValueResult
        TestCase.assertNotNull("Unexpected eval result: $res11", res11e)
        TestCase.assertEquals(3, res11e!!.value)
        
        val codeLine2 = ReplCodeLine(1, "x+4")
        val res2 = repl.replCompiler?.compile(codeLine2, listOf(codeLine1))
        val res2c = res2 as? ReplCompileResult.CompiledClasses
        TestCase.assertNotNull("Unexpected compile result: $res2", res2c)

        val res21 = repl.compiledEvaluator.eval(res2c!!)
        val res21e = res21 as? ReplEvalResult.ValueResult
        TestCase.assertNotNull("Unexpected eval result: $res21", res21e)
        TestCase.assertEquals(5, res21e!!.value)

        Disposer.dispose(disposable)
    }
}

internal class TestRepl(
        disposable: Disposable,
        templateClasspath: List<File>,
        templateClassName: String
) {
    val emptyScriptArgs = ScriptArgsWithTypes(arrayOf(emptyArray<String>()), arrayOf(Array<String>::class))

    private val configuration = KotlinTestUtils.newConfiguration(ConfigurationKind.ALL, TestJdkKind.MOCK_JDK, *templateClasspath.toTypedArray()).apply {
        put(CommonConfigurationKeys.MODULE_NAME, "kotlin-script")
    }

    private fun makeScriptDefinition(templateClasspath: List<File>, templateClassName: String): KotlinScriptDefinition {
        val classloader = URLClassLoader(templateClasspath.map { it.toURI().toURL() }.toTypedArray(), this.javaClass.classLoader)
        val cls = classloader.loadClass(templateClassName)
        return KotlinScriptDefinitionFromAnnotatedTemplate(cls.kotlin, null, null, emptyMap())
    }

    private val scriptDef = makeScriptDefinition(templateClasspath, templateClassName)

    val replCompiler : GenericReplCompiler? by lazy {
        GenericReplCompiler(disposable, scriptDef, configuration, PrintingMessageCollector(System.out, MessageRenderer.WITHOUT_PATHS, false))
    }

    val compiledEvaluator: ReplEvaluator by lazy {
        GenericReplEvaluator(configuration.jvmClasspathRoots, null, emptyScriptArgs, ReplRepeatingMode.NONE)
    }
}

