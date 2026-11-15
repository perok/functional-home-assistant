package fh.view.model

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

/** `core/variable.pkl`'s `Value`: what a viewer can choose, and so the most a
  * start may be. Spelled unescaped into the page's JS and its URL.
  */
type VarValue = String :| VarValue.Token

object VarValue:
  type Token = DescribedAs[
    Match["[A-Za-z0-9_.:-]+"],
    "a variable's value is a plain token ([A-Za-z0-9_.:-]+)"
  ]

  def of(s: String): Option[VarValue] = s.refineOption[Token]
