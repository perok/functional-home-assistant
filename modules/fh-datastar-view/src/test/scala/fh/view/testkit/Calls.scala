package fh.view.testkit

import fh.view.model.CallDecl

extension (d: CallDecl)
  /** The service call a test expects here. */
  def asService: CallDecl.Service = d match
    case s: CallDecl.Service => s
    case other => throw AssertionError(s"a service call, not $other")
