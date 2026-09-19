package fh.view.build

import cats.effect.IO
import fh.view.model.Access
import fh.view.testkit.PklWorkspace

/** Where a dashboard's access rule actually comes from (issue #89).
  *
  * Precedence is resolved in Scala, not Pkl (ADR 0021 rejected a Pkl hoisting
  * mechanism for per-dashboard wire fields), which means the fold lives in
  * `Site.decode` and nothing else re-derives it. That makes this the one place
  * the rule "the dashboard's own wins, else the site's" is decided — and the
  * direction it fails in matters: a fold that silently drops the site default
  * serves an authored-admin site to anyone with a login.
  *
  * Driven through the real Pkl entrypoint rather than hand-built JSON, so it
  * also pins that `site.pkl` and `entry.pkl` actually EMIT the field.
  */
class SiteAccessSuite extends munit.CatsEffectSuite {

  private def decodeSite(siteBody: String): IO[Map[String, Access]] = {
    val tmp = os.temp.dir()
    val _ = PklWorkspace.bootstrap(tmp)
    os.write.over(
      tmp / Site.EntryFile,
      s"""amends "@fh-dashboard/site.pkl"
         |import "@fh-dashboard/components.pkl" as c
         |
         |$siteBody
         |""".stripMargin
    )
    val result = SourceEval
      .eval(tmp, Site.EntryFile)
      .fold(e => fail(s"site eval failed: $e"), identity)
    Site
      .decode(result.value, result.imports)
      .map(
        _.dashboards.collect { case (slug, Right(v)) => slug -> v.access }.toMap
      )
  }

  // Parens around the parent: amending anything that is not a `new` expression
  // is a parse error without them.
  private val oneCard =
    """card = (c.grid) { children { new c.SectionTitle { text = "hi" } } }"""

  test("a dashboard that names no rule inherits the site's") {
    decodeSite(
      s"""access = c.access.admin
         |dashboards {
         |  ["home"] { $oneCard }
         |}""".stripMargin
    ).map(a => assertEquals(a.get("home"), Some(Access.Admin)))
  }

  test("a dashboard's own rule wins over the site's — the wall tablet") {
    decodeSite(
      s"""access = c.access.admin
         |dashboards {
         |  ["home"] { $oneCard }
         |  ["wall"] { access = c.access.public; $oneCard }
         |}""".stripMargin
    ).map { a =>
      assertEquals(a.get("home"), Some(Access.Admin))
      assertEquals(a.get("wall"), Some(Access.Public))
    }
  }

  test("a site that names no rule falls back to the restrictive default") {
    decodeSite(s"""dashboards { ["home"] { $oneCard } }""")
      .map(a => assertEquals(a.get("home"), Some(Access.default)))
  }

  /** The failure that matters is the OPEN one, so it gets its own case: a
    * setting nobody can read must not turn an admin-only site into a public
    * one.
    */
  test("an unreadable site rule is treated as the default, not as public") {
    decodeSite(
      s"""access { kind = "not-a-rule" }
         |dashboards { ["home"] { $oneCard } }""".stripMargin
    ).map(a => assertEquals(a.get("home"), Some(Access.default)))
  }
}
