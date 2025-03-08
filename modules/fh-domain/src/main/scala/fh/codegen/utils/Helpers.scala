package fh.codegen.utils

object Helpers {

  def paramNameSafe(in: String) =
    in match
      case "type" => "`type`" // protected name
      case other  => other

  def objectNameSafe(in: String) =
    in match
      case a @ "notify" => "notify1" // protected name
      case "type"       => "type1" // protected name
      // TODO only look at if safe letters instead
      case other if List(" ", "/", "-", "@", ".").exists(other.contains(_)) =>
        s"`$other`"
      case other if other.nonEmpty && other.charAt(0).isDigit =>
        s"`$other`"
      case other => other
}
