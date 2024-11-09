/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.tinkerpop.gremlin.structure.util.empty.EmptyGraph
import org.apache.tinkerpop.gremlin.process.traversal.translator.GolangTranslator
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine
import org.apache.tinkerpop.gremlin.groovy.jsr223.ast.AmbiguousMethodASTTransformation
import org.apache.tinkerpop.gremlin.groovy.jsr223.ast.VarAsBindingASTTransformation
import org.apache.tinkerpop.gremlin.groovy.jsr223.ast.RepeatASTTransformationCustomizer
import org.apache.tinkerpop.gremlin.groovy.jsr223.GroovyCustomizer
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.apache.tinkerpop.gremlin.language.corpus.FeatureReader

import javax.script.SimpleBindings
import java.nio.file.Paths

import static org.apache.tinkerpop.gremlin.process.traversal.AnonymousTraversalSource.traversal

// getting an exception like:
// > InvocationTargetException: javax.script.ScriptException: groovy.lang.MissingMethodException: No signature of
// > method: org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.DefaultGraphTraversal.mergeE() is applicable for
// > argument types: (String) values: [4ffdea36-4a0e-4681-acba-e76875d1b25b]
// usually means bindings are not being extracted properly by VarAsBindingASTTransformation which typically happens
// when a step is taking an argument that cannot properly resolve to the type required by the step itself. there are
// special cases in that VarAsBindingASTTransformation class which might need to be adjusted. Editing the
// GremlinGroovyScriptEngineTest#shouldProduceBindingsForVars() with the failing step and argument can typically make
// this issue relatively easy to debug and enforce.
//
// getting an exception like:
// > Ambiguous method overloading for method org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource#mergeV.
// likely requires changes to the AmbiguousMethodASTTransformation which forces a call to a particular method overload
// and usually relates to use of null where the type isn't clear

// file is overwritten on each generation
radishGremlinFile = new File("${projectBaseDir}/gremlin-go/driver/cucumber/gremlin.go")

// assumes globally unique scenario names for keys with list of Gremlin traversals as they appear
gremlins = FeatureReader.parseGrouped(Paths.get("${projectBaseDir}", "gremlin-test", "src", "main", "resources", "org", "apache", "tinkerpop", "gremlin", "test", "features").toString())

gremlinGroovyScriptEngine = new GremlinGroovyScriptEngine(
        (GroovyCustomizer) { -> new RepeatASTTransformationCustomizer(new AmbiguousMethodASTTransformation()) },
        (GroovyCustomizer) { -> new RepeatASTTransformationCustomizer(new VarAsBindingASTTransformation()) }
)

translator = GolangTranslator.of('g')
g = traversal().withEmbedded(EmptyGraph.instance())
bindings = new SimpleBindings()
bindings.put('g', g)

radishGremlinFile.withWriter('UTF-8') { Writer writer ->
    writer.writeLine('/*\n' +
            'Licensed to the Apache Software Foundation (ASF) under one\n' +
            'or more contributor license agreements.  See the NOTICE file\n' +
            'distributed with this work for additional information\n' +
            'regarding copyright ownership.  The ASF licenses this file\n' +
            'to you under the Apache License, Version 2.0 (the\n' +
            '"License"); you may not use this file except in compliance\n' +
            'with the License.  You may obtain a copy of the License at\n' +
            '\n' +
            'http://www.apache.org/licenses/LICENSE-2.0\n' +
            '\n' +
            'Unless required by applicable law or agreed to in writing,\n' +
            'software distributed under the License is distributed on an\n' +
            '"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY\n' +
            'KIND, either express or implied.  See the License for the\n' +
            'specific language governing permissions and limitations\n' +
            'under the License.\n' +
            '*/\n')

    writer.writeLine("\n//********************************************************************************")
    writer.writeLine("//* Do NOT edit this file directly - generated by build/generate.groovy")
    writer.writeLine("//********************************************************************************\n")

    writer.writeLine(
        'package gremlingo\n' +
        '\n' +
        'import (\n' +
        '\t \"errors\"\n' +
        '\t \"time\"\n' +
        '\t \"math\"\n' +
        '\t \"github.com/apache/tinkerpop/gremlin-go/v3/driver\"\n' +
        ')\n'
    )

    // We might need a function to copy all the translations

    writer.writeLine(
        'var translationMap = map[string][]func(g *gremlingo.GraphTraversalSource, p map[string]interface{}) *gremlingo.GraphTraversal{'
    )

    // Groovy can't process certain null oriented calls because it gets confused with the right overload to call
    // at runtime. using this approach for now as these are the only such situations encountered so far. a better
    // solution may become necessary as testing of nulls expands.
    def staticTranslate = [
            g_injectXnull_nullX: "  \"g_injectXnull_nullX\": {func(g *gremlingo.GraphTraversalSource, p map[string]interface{}) *gremlingo.GraphTraversal {return g.Inject(nil, nil)}}, ",
            g_VX1X_valuesXageX_injectXnull_nullX: "  \"g_VX1X_valuesXageX_injectXnull_nullX\": {func(g *gremlingo.GraphTraversalSource, p map[string]interface{}) *gremlingo.GraphTraversal {return g.V(p[\"xx1\"]).Values(\"age\").Inject(nil, nil)}}, "
    ]

    gremlins.each { k,v ->
        if (staticTranslate.containsKey(k)) {
            writer.writeLine(staticTranslate[k])
        } else {
            writer.write("    ")
            writer.write("\"")
            writer.write(k)
            writer.write("\": {")
            def collected = v.collect {
                def t = gremlinGroovyScriptEngine.eval(it, bindings)
                [t, t.bytecode.bindings.keySet()]
            }
            def uniqueBindings = collected.collect { it[1] }.flatten().unique()
            def gremlinItty = collected.iterator()
            while (gremlinItty.hasNext()) {
                def t = gremlinItty.next()[0]
                writer.write("func(g *gremlingo.GraphTraversalSource, p map[string]interface{}) *gremlingo.GraphTraversal {return ")
                try {
                    writer.write(translator.translate(t.bytecode).script.
                            replaceAll("xx([0-9]+)", "p[\"xx\$1\"]").
                            replaceAll("v([0-9]+)", "p[\"v\$1\"]").
                            replaceAll("vid([0-9]+)", "p[\"vid\$1\"]").
                            replaceAll("e([0-9]+)", "p[\"e\$1\"]").
                            replaceAll("eid([0-9]+)", "p[\"eid\$1\"]").
                            replace("l1", "p[\"l1\"]").
                            replace("l2", "p[\"l2\"]").
                            replace("pred1", "p[\"pred1\"]").
                            replace("c1", "p[\"c1\"]").
                            replace("c2", "p[\"c2\"]"))
                } catch (ignored) {
                    // Putting these in place of not implemented functions
                    // TODO make sure all is supported
                    writer.write("nil")
                }
                writer.write("}")
                if (gremlinItty.hasNext())
                    writer.write(', ')
            }
            writer.writeLine('}, ')
        }
    }
    writer.writeLine('}\n')

    writer.writeLine(
    '   func GetTraversal(scenarioName string, g *gremlingo.GraphTraversalSource, parameters map[string]interface{}) (*gremlingo.GraphTraversal, error) {\n' +
    '       if traversalFns, ok := translationMap[scenarioName]; ok {\n' +
    '           traversal := traversalFns[0]\n' +
    '           translationMap[scenarioName] = traversalFns[1:]\n' +
    '           return traversal(g, parameters), nil\n' +
    '       } else {\n' +
    '           return nil, errors.New("scenario for traversal not recognized")\n' +
    '       }\n' +
    '   }\n'
    )
}

