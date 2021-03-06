/*
 * Copyright (c) 2018-2019 "Neo4j, Inc." [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.gremlin.translation.translator
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.opencypher.gremlin.translation.CypherAst.parse
import org.opencypher.gremlin.translation.GremlinSteps
import org.opencypher.gremlin.translation.ir.builder.{IRGremlinBindings, IRGremlinPredicates, IRGremlinSteps}
import org.opencypher.gremlin.translation.ir.helpers.CypherAstAssert.__
import org.opencypher.gremlin.translation.ir.helpers.JavaHelpers.assertThatThrownBy
import org.opencypher.gremlin.translation.ir.helpers.TraversalAssertions
import org.opencypher.gremlin.translation.ir.model.{GremlinPredicate, GremlinStep}
import org.opencypher.gremlin.translation.ir.rewrite.{CosmosDbFlavor, CustomFunctionFallback, NeptuneFlavor}
import org.opencypher.gremlin.translation.traversal.DeprecatedOrderAccessor
import org.opencypher.gremlin.traversal.CustomFunction

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class TranslatorBuilderTest {
  @Test
  def cosmosDb(): Unit = {
    val dslBuilder = createBuilder.build("cosmosdb")

    assertThatThrownBy(
      () =>
        parse("RETURN toupper('test')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("Custom functions and predicates are not supported on target implementation: cypherToUpper")

    val steps = parse("MATCH (n) RETURN n.name")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CosmosDbFlavor)).isTrue
    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
    assertContains(steps, __.properties().hasKey("name"))
    assertNotContains(steps, __.values("name"))
  }

  @Test
  def cosmosDbExtensions(): Unit = {
    val dslBuilder = createBuilder.build("cosmosdb+cfog_server_extensions")

    val steps = parse("MATCH (n) RETURN toupper(n.name)")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CosmosDbFlavor)).isTrue
    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
    assertContains(steps, __.map(CustomFunction.cypherToUpper()))
    assertContains(steps, __.properties().hasKey("name"))
    assertNotContains(steps, __.values("name"))
  }

  @Test
  def neptuneDb(): Unit = {
    val dslBuilder = createBuilder.build("neptune")

    assertThatThrownBy(
      () =>
        parse("RETURN toupper('test')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("Custom functions and predicates are not supported on target implementation: cypherToUpper")

    val steps = parse("MATCH (n) WHERE n.age=$age RETURN count(n)", Map("age" -> 25).asJava)
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(NeptuneFlavor)).isTrue
    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
    assertContains(steps, __.constant(25)) // inline parameters
    assertContains(steps, __.count().barrier())
  }

  @Test
  def neptuneExtensions(): Unit = {
    val dslBuilder = createBuilder.build("neptune+cfog_server_extensions")

    val steps = parse("MATCH (n) WHERE toupper(n.age)=$age RETURN count(n)", Map("age" -> 25).asJava)
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(NeptuneFlavor)).isTrue
    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
    assertContains(steps, __.constant(25)) // inline parameters
    assertContains(steps, __.count().barrier())
  }

  @Test
  def gremlin(): Unit = {
    val dslBuilder = createBuilder.build("gremlin")

    assertThatThrownBy(
      () =>
        parse("RETURN toupper('test')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("Custom functions and predicates are not supported on target implementation: cypherToUpper")

    assertThatThrownBy(
      () =>
        parse("RETURN gremlin('g.V()')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("needs to be explicitly enabled")

    val steps = parse("MATCH (n) RETURN n.name")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
  }

  @Test
  def gremlin33x(): Unit = {
    val dslBuilder = createBuilder.build("gremlin33x")

    assertThatThrownBy(
      () =>
        parse("RETURN toupper('test')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("Custom functions and predicates are not supported on target implementation: cypherToUpper")

    val steps = parse("MATCH (n) RETURN n.name ORDER BY n.name")
      .buildTranslation(dslBuilder)

    assertContains(steps, __.by(__.select("n.name"), DeprecatedOrderAccessor.incr))
    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
  }

  @Test
  def empty(): Unit = {
    val dslBuilder = createBuilder.build("")

    assertThatThrownBy(
      () =>
        parse("RETURN toupper('test')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("Custom functions and predicates are not supported on target implementation: cypherToUpper")

    val steps = parse("MATCH (n) RETURN n.name")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
  }

  @Test
  def nullParam(): Unit = {
    val dslBuilder = createBuilder.build("")

    assertThatThrownBy(
      () =>
        parse("RETURN toupper('test')")
          .buildTranslation(dslBuilder))
      .hasMessageContaining("Custom functions and predicates are not supported on target implementation: cypherToUpper")

    val steps = parse("MATCH (n) RETURN n.name")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
  }

  @Test
  def gremlinExtensions(): Unit = {
    val dslBuilder = createBuilder.build("gremlin+cfog_server_extensions")

    val steps = parse("MATCH (n) RETURN toupper(n.name)")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
  }

  @Test
  def multiplePlus(): Unit = {
    val dslBuilder = createBuilder.build("gremlin++cfog_server_extensions+")

    val steps = parse("MATCH (n) RETURN toupper(n.name)")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.flavor().rewriters.contains(CustomFunctionFallback)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse
  }

  @Test
  def gremlinFunction(): Unit = {
    val dslBuilder = createBuilder.build("gremlin+experimental_gremlin_function")

    val steps = parse("RETURN gremlin(\"g.V().hasLabel(\'inject\')\")")
      .buildTranslation(dslBuilder)

    assertThat(dslBuilder.isEnabled(TranslatorFeature.EXPERIMENTAL_GREMLIN_FUNCTION)).isTrue
    assertThat(dslBuilder.isEnabled(TranslatorFeature.CYPHER_EXTENSIONS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.MULTIPLE_LABELS)).isFalse
    assertThat(dslBuilder.isEnabled(TranslatorFeature.RETURN_GREMLIN_ELEMENTS)).isFalse

    assertContains(steps, __.V().hasLabel("inject"))
  }

  @Test
  def invalidTranslator(): Unit = {
    assertThatThrownBy(() => createBuilder.build("not_existing+cfog_server_extensions"))
      .hasMessageContaining("Unknown translator type: not_existing in `not_existing+cfog_server_extensions`")
  }

  @Test
  def invalidFeature(): Unit = {
    assertThatThrownBy(() => createBuilder.build("cosmosdb+not_existing"))
      .hasMessageContaining("Unknown translator feature: not_existing in `cosmosdb+not_existing`")
  }

  @Test
  def allFlavorsSupported(): Unit = {
    allFlavors.foreach(
      createBuilder.build(_)
    )
  }

  @Test
  def allFlavorsSupportedignoringCase(): Unit = {
    allFlavors
      .map(_.toUpperCase())
      .foreach(
        createBuilder.build(_)
      )
  }

  @Test
  def allFlavorsInErrorMessage(): Unit = {
    Try(createBuilder.build("not_existing")) match {
      case Success(_) => throw new IllegalStateException("Expected to fail")
      case Failure(e) => assertThat(e.getMessage).contains(allFlavors.map(_.toLowerCase): _*)
    }
  }

  private def allFlavors =
    classOf[TranslatorFlavor].getDeclaredMethods
      .filter(_.getReturnType == classOf[TranslatorFlavor])
      .filter(_.getParameterTypes.length == 0)
      .map(_.getName)

  private def createBuilder =
    Translator
      .builder()
      .custom(
        new IRGremlinSteps,
        new IRGremlinPredicates,
        new IRGremlinBindings
      )

  private def assertContains(steps: Seq[GremlinStep], traversal: GremlinSteps[Seq[GremlinStep], GremlinPredicate]) =
    TraversalAssertions.traversalContains("Traversal", steps, traversal.current())

  private def assertNotContains(steps: Seq[GremlinStep], traversal: GremlinSteps[Seq[GremlinStep], GremlinPredicate]) =
    TraversalAssertions.traversalNotContains("Traversal", steps, traversal.current())
}
