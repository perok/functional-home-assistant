package fh.view.model

import fh.view.runtime.{Cel, Templates}

/** What `Dashboard.validate` checks: every rule across fields or nodes, as
  * human-readable errors. A rule about one value is its type's, at decode.
  */
private[model] object Validate:

  def errors(
      d: Dashboard,
      locateTransform: String => Option[String],
      compiled: Map[String, Either[String, Templates.Card]]
  ): List[String] =
    import d.*
    val cardReads: Map[String, Either[String, Templates.Reads]] =
      compiled.view.mapValues(_.map(_.reads)).toMap

    // A section marks its names optional, and the library guards one name by
    // another's section (`{{#service}}…{{targetId}}`), so only a name outside
    // every section must be set; a misspelt section shows as its slot unread.
    def templateErrors(
        nodeId: String,
        cardName: String,
        slotNames: Set[String],
        filled: Set[String]
    ): List[String] =
      cards.get(cardName) match
        case None =>
          List(s"$nodeId: references unknown card '$cardName'")
        case Some(cd) =>
          val undeclared = (filled -- cd.regions.keySet).toList.sorted.map(r =>
            s"$nodeId: fills region '$r', which card '$cardName' does not " +
              "declare — its children would never render"
          ) ++ slotNames
            .intersect(cd.regions.keySet)
            .toList
            .sorted
            .map(r =>
              s"$nodeId: slot '$r' is named like card '$cardName''s region, " +
                "whose children its hole shows instead — rename the slot"
            )
          val names = cardReads.get(cardName).flatMap(_.toOption)
          val known = slotNames ++ Dashboard.injectedStatic ++
            cd.regions.keySet ++
            Option.when(cd.bakedRegions.nonEmpty)(Dashboard.BakeIndexVar)
          val unset = names.toList
            .flatMap(_.required)
            .filterNot(known)
            .sorted
          val read = names.toList.flatMap(_.names).toSet
          val unread = names.toList.flatMap(_ =>
            slotNames.toList.sorted.filterNot(s =>
              read(s) || Dashboard.readByRenderer(s)
            )
          )
          val dotted = slotNames
            .filter(_.contains('.'))
            .toList
            .sorted
            .map(s =>
              s"$nodeId: slot '$s' has a '.' in its name, which a template " +
                "reads as one of its views ({{{x.bind}}}) — rename the slot"
            )
          undeclared ++ dotted ++
            Option
              .when(unset.nonEmpty)(
                s"$nodeId: card '$cardName' missing slots: " +
                  unset.mkString(", ") +
                  " — its template reads them; set \"\" for nothing"
              )
              .toList ++
            Option
              .when(unread.nonEmpty)(
                s"$nodeId: card '$cardName' never reads slots: " +
                  unread.mkString(", ") +
                  " — its template names none of them; drop them"
              )
              .toList

    def isBoolLit(v: Value): Boolean = v match
      case Value.Literal(_: Lit.Bool) => true
      case _                          => false

    def number(v: Value): Option[Double] = v match
      case Value.Literal(Lit.Whole(n)) => Some(n.toDouble)
      case Value.Literal(Lit.Real(d))  => Some(d)
      case _                           => None

    def degenerate(lo: Value, hi: Value): Boolean =
      number(lo).exists(a => number(hi).contains(a))

    def range(lo: Value, hi: Value): String =
      s"${number(lo).getOrElse("?")}..${number(hi).getOrElse("?")}"

    def slotErrors(
        nodeId: String,
        cardName: String,
        slots: Map[SlotKey, BoundSlot],
        scope: Map[String, String]
    ): List[String] =
      labelled(slots).flatMap { case (key, name, src) =>
        val stepErrors =
          // A fetch slot's steps are `fetchErrors`' to check.
          if (src.fetch.isDefined) Nil
          else
            Option
              .when(src.expr.readsMissingSubject)(
                s"$nodeId: slot '$name' reads the card's entity, and the card " +
                  "has none — name one, as `c.exprOf(e, …)` or `q.entity(e)` " +
                  "does"
              )
              .toList ++ src.expr.steps.flatMap {
              case Step.Percent(lo, hi) if degenerate(lo, hi) =>
                Some(
                  s"$nodeId: slot '$name' has a degenerate percent range " +
                    s"(${range(lo, hi)}) — it would divide by zero"
                )
              case Step.Fill(lo, hi) if degenerate(lo, hi) =>
                Some(
                  s"$nodeId: slot '$name' has a degenerate fill range " +
                    s"(${range(lo, hi)}) — it would divide by zero"
                )
              // Only a hand-written scale gets here (the Pkl helper answers
              // null for a unit it cannot scale).
              case Step.Duration(scale) if scale <= 0 =>
                Some(
                  s"$nodeId: slot '$name' has a non-positive duration scale " +
                    s"($scale) — every reading would render '0s'"
                )
              // CEL needs one type across the arms, so a mixed Match has no
              // CEL equivalent (ADR 0028). A read as `otherwise` is text.
              case Step.Match(cases, otherwise)
                  if (isBoolLit(otherwise) :: cases.values.toList.map {
                    case _: Lit.Bool => true
                    case _           => false
                  }).distinct.sizeIs > 1 =>
                Some(
                  s"$nodeId: slot '$name' has a match with both string and " +
                    "boolean arms — pick one; a boolean arm is for a boolean " +
                    "attribute (disabled, hidden), a string for everything else"
                )
              // Only a hand-written wire gets here.
              case s: Step.FetchStage =>
                Some(
                  s"$nodeId: slot '$name' has a ${Step.label(s)} step but reads no " +
                    "fetch — a stage turns a provider's answer into the " +
                    "hole's content, and a state slot has no answer to turn"
                )
              case _ => None
            }
        val ownBypass = src.expr.source match
          case Value.Entity(_, Part.State | Part.Whole, _, _) => !src.isBool
          case _                                              => false
        val bypassErrors =
          (src.expr.bypassError(ownBypass) ++
            src.values.values.flatMap(_.bypassError(ownMay = false))).headOption
            .map(e => s"$nodeId: slot '$name': $e")
            .toList
        stepErrors ++ bypassErrors ++ signalErrors(
          nodeId,
          cardName,
          key,
          src
        ) ++
          readErrors(nodeId, cardName, name, src) ++
          fetchErrors(nodeId, cardName, name, src, scope) ++
          src.inputs
            .map(_.source)
            .collect { case Value.Fetch(f) =>
              askErrors(nodeId, name, f, scope)
            }
            .flatten
      }

    /** A fetch's references are declared above it, its declared values pick an
      * option, and every option it could pick is a window HA answers; a
      * viewer's value is checked at the write.
      */
    def askErrors(
        nodeId: String,
        name: String,
        template: FetchTemplate,
        scope: Map[String, String]
    ): List[String] =
      val refErrors = template.references.distinct.sorted
        .filterNot(scope.contains)
        .map(v =>
          s"$nodeId: slot '$name' reads the variable '$v', which no " +
            "ancestor declares — declare it on the node that owns the " +
            "choice, or write the value down"
        )
      if (refErrors.nonEmpty) refErrors
      else
        (template
          .resolve(scope)
          .left
          .toOption
          .map(e =>
            s"$nodeId: slot '$name' reads the variable " +
              s"'${template.references.mkString}' at its declared value: $e"
          ) ++ template.window.all
          .flatMap(_.problem)
          .distinct
          .map(p => s"$nodeId: slot '$name' $p")).toList

    /** Otherwise a bad fetch renders blank forever with nothing saying why. */
    def fetchErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot,
        scope: Map[String, String]
    ): List[String] =
      src.fetch.toList.flatMap { template =>
        val parseErrors = askErrors(nodeId, name, template, scope)
        val stageError = src.expr.steps match {
          case Nil | List(_: Step.FetchStage) => None
          case _                              =>
            Some(
              s"$nodeId: slot '$name' reads a fetch, so its one step may be " +
                "a stage such as a chart — for CEL over it, name the fetch as an input of a " +
                "slot that reads state"
            )
        }
        val rawHole =
          cardReads.get(cardName).flatMap(_.toOption).flatMap { reads =>
            val hasRaw = reads.raw(name)
            val markup = src.stage.exists(Step.draws)
            if (!markup && hasRaw)
              Some(
                s"$nodeId: card '$cardName' places slot '$name' in a RAW " +
                  "hole, but its value is DATA — write " +
                  s"{{$name}}, or the page emits unescaped JSON"
              )
            else if (markup && !hasRaw)
              Some(
                s"$nodeId: card '$cardName' places slot '$name' in an " +
                  "ESCAPED hole, but its transform draws markup — write " +
                  s"{{{$name}}}, or the page shows the markup as text"
              )
            else None
          }
        parseErrors ++ stageError.toList ++
          rawHole.toList
      }

    // A value is read with no subject, so an unnamed read reads nothing; one
    // that fetches is read as the answer, which its steps would never see.
    def inputErrors(
        nodeId: String,
        what: String,
        v: ValueExpr,
        env: List[(String, ValueType)]
    ): List[String] =
      v.typed(env)
        .left
        .toOption
        .toList
        .map(m => s"$nodeId: $what ${m.message}") ++
        Option
          .when(v.readsMissingSubject)(
            s"$nodeId: $what reads an entity it does not name — an " +
              "expression value has no subject"
          )
          .toList ++
        Option
          .when(v.steps.nonEmpty && (v.source match
            case _: Value.Fetch => true
            case _              => false))(
            s"$nodeId: $what fetches, so it is read as the answer, with no steps"
          )
          .toList

    /** At the declaration, so an unreferenced variable is still wrong loudly.
      */
    // Bound to every slot that reads one, so "read by no slot" is the
    // binding's absence.
    def valueErrors(nodeId: String, c: LayoutNode.Component): List[String] =
      val read = c.bound.values.flatMap(_.values.keySet).toSet
      c.values.keys.toList.sorted.flatMap { name =>
        if (!Dashboard.ExpressionValueName.matches(name))
          List(
            s"$nodeId: expression value '$name' is not a name an expression " +
              "can read — letters, digits and '_', not starting with a digit"
          )
        else if (Cel.fixedNames.contains(name))
          List(
            s"$nodeId: expression value '$name' would hide the '$name' every " +
              "expression already reads — rename it"
          )
        else if (!read.contains(name))
          List(
            s"$nodeId: expression value '$name' is read by no expression on " +
              "this node — names are the node's own and are not inherited"
          )
        else
          // A value is shared by content across nodes, so it has no subject.
          val v = c.values(name)
          v.unnamedComparison(
            s"$nodeId: expression value '$name'",
            v.typed(Nil)
          ).fold(inputErrors(nodeId, s"expression value '$name'", v, Nil))(
            List(_)
          )
      } ++ (
        labelled(c.bound).map((_, n, s) =>
          (s"slot '$n'", s.expr, ValueExpr.env(c.values))
        ) ++ c.values.toList
          .sortBy(_._1)
          .map((n, v) => (s"expression value '$n'", v, Nil))
      ).flatMap { (where, expr, env) =>
        // Each CEL's own at any depth, read by that CEL; the node's are in
        // scope beside, but not inside a guard or an order term.
        expr.scoped.flatMap { (e, nodeScope) =>
          e.cels.flatMap { (cel, own) =>
            val read = Cel.identifiers(cel)
            own.keys.toList.sorted.flatMap { name =>
              if (
                !Dashboard.ExpressionValueName.matches(name) ||
                Cel.fixedNames.contains(name)
              )
                List(
                  s"$nodeId: $where has an expression value '$name' its " +
                    "expression cannot read — letters, digits and '_', and not " +
                    s"one of ${Cel.fixedNames.toList.sorted.mkString(", ")}"
                )
              else if (!read.contains(name))
                List(
                  s"$nodeId: $where has an expression value '$name' its " +
                    "expression does not read"
                )
              else
                inputErrors(
                  nodeId,
                  s"$where's expression value '$name'",
                  own(name),
                  if (nodeScope) env else Nil
                )
            }
          }
        }
      } ++ labelled(c.bound).map((_, n, s) => (n, s)).collect {
        // The once-cache keys by what a value IS, not by what it counts now.
        case (slot, src)
            if src.reads == Reads.Once && src.inputs.exists(_.isLive) =>
          s"$nodeId: slot '$slot' reads a live count, condition or fetch but is " +
            s"read '${Reads.Once.wire}' — it would show the first count forever; " +
            s"read it '${Reads.Live.wire}'"
        case (slot, src)
            if src.reads == Reads.Once && src.valueEntities.nonEmpty =>
          s"$nodeId: slot '$slot' reads another entity but is read " +
            s"'${Reads.Once.wire}' — it would show its first state forever; read " +
            s"it '${Reads.Live.wire}'"
        // Pkl names every entity a slot reads from it, so it cannot move.
        case (slot, src)
            if slot == Dashboard.SubjectSlot && src.literal.isEmpty =>
          s"$nodeId: slot '${Dashboard.SubjectSlot}' must be a literal — it " +
            "names the entity the card's other slots read, which is a " +
            "build-time fact"
      } ++ labelled(c.bound).flatMap((_, n, s) =>
        s.expr.unnamedComparison(s"$nodeId: slot '$n'", s.typed)
      )

    def valueWhileErrors(
        nodeId: String,
        slots: Map[SlotKey, BoundSlot]
    ): List[String] =
      labelled(slots).flatMap { (_, name, src) =>
        src.valueWhile.toList.flatMap { w =>
          Option
            .when(w.fetch.isDefined || w.fetches.nonEmpty)(
              s"$nodeId: slot '$name' has a valueWhile that reads a fetch — " +
                "it is decided before any fetch is read"
            )
            .toList ++
            Option
              .when(src.reads == Reads.Once)(
                s"$nodeId: slot '$name' is read '${Reads.Once.wire}' and has a " +
                  "valueWhile — it would keep its first answer forever"
              )
              .toList ++
            w.expr.unnamedComparison(
              s"$nodeId: slot '$name''s valueWhile",
              w.typed
            )
        }
      }

    // A fetch slot's steps are `fetchErrors`' to check.
    def typeErrors(
        nodeId: String,
        slots: Map[SlotKey, BoundSlot]
    ): List[String] =
      // An attribute is `dyn` to CEL, but read bare it is text or a number far
      // more often than a bool.
      def bareAttr(src: BoundSlot) = (src.expr.source, src.expr.steps) match
        case (Value.Entity(_, _: Part.Attr, _, _), Nil) => true
        case _                                          => false
      labelled(slots).flatMap { case (_, name, src) =>
        if (src.fetch.isDefined) Nil
        else
          src.typed match
            case Left(ValueExpr.Mistyped(err, Some(t))) =>
              val at = locateTransform(t).fold("")(loc => s" (at $loc)")
              List(s"$nodeId: slot '$name' has an invalid transform$at: $err")
            case Left(ValueExpr.Mistyped(err, None)) =>
              List(s"$nodeId: slot '$name' $err")
            case Right(t) if src.constant.isDefined =>
              Option
                .unless(ValueType.fits(t.out, src.`type`))(
                  s"$nodeId: slot '$name' holds a ${t.out.name} literal, " +
                    "which a " + (
                      if (src.isBool) "bool slot does not take"
                      else "text slot does not take — a flag's slot is slotMod.bool"
                    )
                )
                .toList
            case Right(t)
                if !ValueType
                  .fits(t.out, src.`type`) || src.isBool && bareAttr(src) =>
              List(
                if (src.isBool)
                  s"$nodeId: slot '$name' is a boolean slot, but its reading " +
                    s"does not produce a bool (a ${t.out.name}) — compare it " +
                    "(`on > 0`), or use a condition"
                else
                  s"$nodeId: slot '$name' yields a ${t.out.name}, which a " +
                    "card cannot show — make it text, `str(…)` or `string(…)`"
              )
            case _ => Nil
      } ++ labelled(slots).collect {
        case (_, name, src)
            if src.signal.exists(_.isInstanceOf[SignalBind.Class]) &&
              !src.isBool =>
          s"$nodeId: slot '$name' binds a class, which is on or off — " +
            "declare the slot bool"
        case (_, name, src) if src.isBool && src.signal.exists {
              case SignalBind.Text | _: SignalBind.Style => true
              case _                                     => false
            } =>
          s"$nodeId: slot '$name' is a bool, which a text or style binding " +
            "prints as `true` — bind it as a class or an attribute"
        case (_, name, src)
            if src.default.exists(_.isInstanceOf[Lit.Bool]) != src.isBool &&
              src.default.isDefined =>
          s"$nodeId: slot '$name' has a default of another type than the " +
            s"slot's (${if (src.isBool) "bool" else "text"})"
      }

    def varErrors(
        nodeId: String,
        vars: List[Dashboard.Declaration]
    ): List[String] =
      vars
        .groupBy(_.name)
        .collect {
          case (name, ds) if ds.sizeIs > 1 =>
            s"$nodeId: declares the variable '$name' more than once"
        }
        .toList
        .sorted ++ vars.sortBy(_.name).flatMap { d =>
        Option
          .when(!d.name.matches("[A-Za-z][A-Za-z0-9_]*"))(
            s"$nodeId: variable '${d.name}' is not a plain name " +
              "([A-Za-z][A-Za-z0-9_]*) — it is spelled into signal names and " +
              "the URL mirror"
          )
          .toList ++ d.initialTransform.flatMap(t =>
          Cel
            .parse(t.celKey)
            .left
            .toOption
            .map(e =>
              s"$nodeId: the initial transform for '${d.name}' does not " +
                s"compile: $e"
            )
        )
      }

    /** `<slot>.jsValue` has no answer for a live non-signal slot (ADR 0017);
      * without this the var renders empty and the handler is silently
      * malformed.
      */
    def readErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot
    ): List[String] =
      if (
        src.constant.isDefined || src.signal.isDefined ||
        src.reads == Reads.Once
      ) Nil
      else
        viewsOf(cardName)
          .filter(_((name, Templates.View.JsValue)))
          .map(_ =>
            s"$nodeId: card '$cardName' reads slot '$name' as {{{$name.jsValue}}}, " +
              "but the slot is live and not a signal — its value moves in the " +
              "element's bytes, so make it a signal slot or a literal"
          )

    def viewsOf(cardName: String): List[Set[(String, Templates.View)]] =
      cardReads.get(cardName).flatMap(_.toOption).map(_.views).toList

    // Each of these otherwise fails silently: the value stops updating.
    def signalErrors(
        nodeId: String,
        cardName: String,
        key: SlotKey,
        src: BoundSlot
    ): List[String] = key match
      case CellKey.Class(cls) => cellClassErrors(nodeId, cls, src)
      case CellKey.Style(p)   => cellStyleErrors(nodeId, p, src)
      case name: String => templateSignalErrors(nodeId, cardName, name, src)

    def templateSignalErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot
    ): List[String] =
      if (src.signal.isEmpty) Nil
      else if (src.constant.isDefined)
        List(
          s"$nodeId: slot '$name' is a constant literal and cannot be a " +
            "signal slot — a value that never moves has nothing to patch"
        )
      else
        // The card must place the value's consumer, or the patch form
        // withholds it and nothing puts it back. A `Handler` has no binding, so
        // one of its two read forms must appear instead.
        val placed =
          if (src.signal.contains(SignalBind.Handler))
            List(Templates.View.JsValue, Templates.View.Signal)
          else List(Templates.View.Bind)
        viewsOf(cardName)
          .filterNot(views => placed.exists(v => views((name, v))))
          .map(_ =>
            s"$nodeId: card '$cardName' has slot '$name' marked as a signal " +
              s"slot, but no part of its template places " +
              placed.map(Dashboard.spell(name, _)).mkString(" or ") +
              " — the value would stop updating"
          )

    def childErrors(
        kids: Map[String, List[LayoutNode]],
        prefix: String,
        id: NodeId,
        scope: Map[String, String]
    ): List[String] =
      LayoutNode.steps(kids).flatMap { case (step, n) =>
        walk(n, prefix, LayoutNode.childId(prefix, id, step, n), scope)
      }

    // Interpolated into a `class` attribute unescaped.
    def classTokenErrors(nodeId: String, cls: String): List[String] =
      Option
        .when(!cls.matches("[A-Za-z0-9_-]+"))(
          s"$nodeId: cell class '$cls' is not a plain CSS class token " +
            "([A-Za-z0-9_-]+)"
        )
        .toList

    // A literal style is no cell slot, so nothing would ever write it.
    def cellErrors(nodeId: String, cell: Option[Cell]): List[String] =
      cell.toList
        .flatMap(_.classes.keys)
        .flatMap(classTokenErrors(nodeId, _)) ++
        cell.toList.flatMap(_.styles.collect {
          case (p, s) if s.constant.isDefined =>
            s"$nodeId: cellStyle '$p' must read live state — anything else " +
              "is never bound"
        })

    // A set has no slots to bind a reading with; Pkl's builders refuse one too.
    def setCellErrors(setId: String, cell: Option[Cell]): List[String] =
      cellErrors(setId, cell) ++ cell.toList
        .flatMap(_.live.map {
          case (CellKey.Class(cls), _) => s"cell class '$cls'"
          case (CellKey.Style(p), _)   => s"cell style '$p'"
        })
        .map(what =>
          s"$setId: a set's $what reads state, which only a card node can " +
            "bind — put it on the clause's node"
        )

    // The renderer places the binding itself, so only the shape can be wrong —
    // and each wrong shape is a class that silently never moves.
    def cellClassErrors(
        nodeId: String,
        cls: String,
        src: BoundSlot
    ): List[String] =
      Option
        .when(src.reads != Reads.Live)(
          s"$nodeId: live cell class '$cls' must read live state — anything " +
            "else is never bound"
        )
        .toList

    def cellStyleErrors(
        nodeId: String,
        property: String,
        src: BoundSlot
    ): List[String] =
      Option
        .when(!Dashboard.CustomProperty.matches(property))(
          s"$nodeId: cellStyle '$property' is not a custom property " +
            "(`--name`) — a plain property would fight the theme's framework"
        )
        .toList ++
        Option
          .when(src.reads != Reads.Live)(
            s"$nodeId: cellStyle '$property' must read live state — " +
              "anything else is never bound"
          )
          .toList ++
        Option
          .when(src.typed.exists(_.out == ValueType.Bool))(
            s"$nodeId: cellStyle '$property' reads a bool, which is no CSS " +
              "value — use cellClassWhen for an on/off look"
          )
          .toList

    // In label order, for messages that read the same on every build.
    def labelled(
        slots: Map[SlotKey, BoundSlot]
    ): List[(SlotKey, String, BoundSlot)] =
      slots.toList.map((k, s) => (k, SlotKey.label(k), s)).sortBy(_._2)

    def noWrap(cardName: String): Boolean =
      cards.get(cardName).exists(!_.wrapAsCell)

    def walk(
        node: LayoutNode,
        prefix: String,
        nodeId: NodeId,
        scope: Map[String, String]
    ): List[String] =
      node match
        case c @ LayoutNode.Component(card, slots, _, cell, _, vars, _) =>
          val wrapErrors =
            if (!noWrap(card)) Nil
            else
              val live = BoundSlot.liveEntities(c.bound.values)
              Option
                .when(live.nonEmpty)(
                  s"$nodeId: card '$card' has wrapAsCell=false but binds live " +
                    s"entities (${live.mkString(", ")}) — an " +
                    "unwrapped node has no morph target, so its live updates " +
                    "would never apply; make those slots literal or " +
                    s"reads='${Reads.OnRender.wire}', or drop the opt-out"
                )
                .toList ++
                Option
                  .when(cell.isDefined)(
                    s"$nodeId: card '$card' has wrapAsCell=false but carries " +
                      "cell params — they ride on the .fh-cell wrapper this " +
                      "card opts out of"
                  )
                  .toList
          val here = scope ++ c.defaults
          // Each `valueWhile` is checked as the slot it is, under its own name.
          val checked = c.bound ++ c.bound.flatMap((k, s) =>
            s.valueWhile.map((SlotKey.label(k) + ".valueWhile": SlotKey) -> _)
          )
          templateErrors(
            nodeId,
            card,
            slots.keySet,
            c.regions.keySet
          ) ++ slotErrors(nodeId, card, checked, here) ++
            varErrors(nodeId, vars) ++
            valueErrors(nodeId, c) ++
            valueWhileErrors(nodeId, c.bound) ++
            typeErrors(nodeId, checked) ++
            cellErrors(nodeId, cell) ++
            wrapErrors ++ childErrors(c.regions, prefix, nodeId, here)
        // Clauses carry complete nodes, validated as ordinary ones.
        case s: LayoutNode.SetNode =>
          val setId = nodeId
          setCellErrors(setId, s.cell) ++
            s.orderBy
              .flatMap(t =>
                t.by.typed(Nil).left.toOption.map(_.message) orElse
                  t.by.bypassError(ownMay = false)
              )
              .map(e => s"$setId: an order term cannot be read: $e") ++
            s.members.toList.sortBy(_._1).flatMap { (candidate, m) =>
              m.clauses
                .flatMap(_.when)
                .flatMap(conditionError)
                .map(e =>
                  s"$setId: a clause of '$candidate' does not decide: $e"
                )
            } ++
            s.candidates.filterNot(s.members.contains).map { c =>
              s"$setId: candidate '$c' has no member entry — it could never " +
                "render, so the build dropped it inconsistently"
            } ++
            s.members.toList.sortBy(_._1).flatMap { case (candidate, m) =>
              m.clauses.zipWithIndex.flatMap { case (clause, i) =>
                walk(
                  clause.node,
                  prefix,
                  LayoutNode.childId(
                    prefix,
                    nodeId,
                    LayoutNode.Step(LayoutNode.DefaultRegion, i),
                    clause.node
                  ),
                  scope
                ) ++ (clause.node match {
                  case c: LayoutNode.Component if noWrap(c.card) =>
                    List(
                      s"$setId/$candidate: card '${c.card}' has " +
                        "wrapAsCell=false and cannot be a set clause — every " +
                        "member is wrapped as its own patch target"
                    )
                  case _ => Nil
                })
              }
            }

    // Structure is never a patch target, so a live bytes slot on it never
    // reaches the DOM; a signal slot needs no patch target (ADR 0017). The Pkl
    // layer checks this too, but a pushed wire bypasses it.
    val structureLiveSlotErrors: List[String] = {
      def walk(node: LayoutNode): List[String] = node match {
        case c: LayoutNode.Component =>
          val asBytes = BoundSlot.liveEntitiesAsBytes(c.bound.values)
          val here = cards
            .get(c.card)
            .filter(_.isStructure)
            .toList
            .filter(_ => asBytes.nonEmpty)
            .map(_ =>
              s"card '${c.card}' holds regions and so is never a patch " +
                s"target, but this node binds live entities " +
                s"(${asBytes.sorted.mkString(", ")}) as BYTES — they would " +
                "never reach the DOM. Put the live markup in a region as a " +
                "node, or make the slot a signal slot"
            )
          here ++ c.allChildren.flatMap(walk)
        case s: LayoutNode.SetNode =>
          s.members.toList
            .sortBy(_._1)
            .flatMap(_._2.clauses)
            .flatMap(cl => walk(cl.node))
      }
      (walk(card) ++ surfaces.toList
        .sortBy(_._1)
        .flatMap(s => walk(s._2.content))).distinct
    }

    // A region renders its children under fixed ids, so it is placed exactly
    // once and unconditionally: twice duplicates the ids, inside a section the
    // server patches ids the page may not have, and with no hole nothing shows.
    val regionHoleErrors: List[String] =
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        cardReads(name) match
          case Left(e) =>
            List(s"card '$name': its template does not compile: $e")
          case Right(reads) =>
            cd.regions.toList.sortBy(_._1).flatMap { case (r, region) =>
              val hole = cd.holeOf(r)
              if reads.misplacedRegions(r) then
                List(
                  s"card '$name': region '$r' is placed other than as $hole " +
                    "outside every section (escaped, as a section, or " +
                    s"inside one) — write $hole, where {{#$r}}{{{html}}}{{/$r}} " +
                    "was"
                )
              else
                reads.regionHoles.getOrElse(r, 0) match
                  case 0 =>
                    List(
                      s"card '$name': declares region '$r' (${region.fill}) " +
                        s"but its `template` places no $hole hole for it — " +
                        "nothing would ever appear there"
                    )
                  case 1 => Nil
                  case n =>
                    List(
                      s"card '$name': places region '$r' $n times — its " +
                        "children would render twice under the same ids"
                    )
            }
      }

    val viewErrors: List[String] =
      cards.keys.toList.sorted.flatMap { name =>
        cardReads(name).toOption.toList.flatMap { reads =>
          reads.unknownViews.toList.sorted.map(t =>
            s"card '$name': its template reads '$t', but a slot's views are " +
              Templates.View.values.map("." + _.spelling).mkString(", ")
          ) ++ reads.boundInOwnSection.toList.sorted.map(s =>
            // ADR 0017: the patch form withholds a signal's value, a false
            // section, which would take its element and binding with it.
            s"card '$name': places {{{$s.bind}}} inside a section on '$s' " +
              s"itself, which the first patch closes — guard it with " +
              s"{{#$s.has}}"
          ) ++ reads.unguardedSignals.toList.sorted.map(s =>
            s"card '$name': places {{$s.signal}} outside {{#$s.signal}}, so " +
              s"a node whose '$s' is not a signal prints a bare `$$` — wrap " +
              s"it in $s.ifSignal(…)"
          )
        }
      }

    // Keeps the id grammar's two shapes apart ([[LayoutNode.segment]]): an
    // all-digit name would read as an index.
    val regionNameErrors: List[String] =
      cards.toList.sortBy(_._1).flatMap { case (name, cd) =>
        cd.regions.keys.toList.sorted.collect {
          case r if r.isEmpty || !r.matches("[A-Za-z0-9_]+") =>
            s"card '$name': region name '$r' is not a plain token " +
              "([A-Za-z0-9_]+) — region names enter node ids"
          case r if r.forall(_.isDigit) =>
            s"card '$name': region name '$r' is all digits, which a node id " +
              "cannot tell from a child index — name it something a number " +
              "could not be"
        }
      }

    /** What an authored `id` has to satisfy: a plain token, used once, and not
      * inside a candidate set's clause.
      *
      * Deliberately NOT a rule: that an id must not read as another node's
      * descendant. Ancestry comes from [[fh.view.runtime.NodeAncestry]], which
      * asks the tree, so `detail` and `detail_0` may be unrelated.
      */
    val authoredIdErrors: List[String] = {
      def walkIds(
          node: LayoutNode,
          prefix: String,
          id: NodeId,
          inSet: Boolean
      ): List[(NodeId, Boolean, Option[String])] =
        (id, inSet, node.authoredId) :: (node match {
          case c: LayoutNode.Component =>
            LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              walkIds(
                ch,
                prefix,
                LayoutNode.childId(prefix, id, step, ch),
                inSet
              )
            }
          // A clause node renders once per member, so every member would
          // claim its id.
          case s: LayoutNode.SetNode =>
            s.members.toList.sortBy(_._1).flatMap { case (_, m) =>
              m.clauses.flatMap(cl => walkIds(cl.node, prefix, id, true))
            }
        })

      val all =
        walkIds(card, "", LayoutNode.rootId("", card), false) ++
          surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
            val p = LayoutNode.surfacePrefix(sid)
            walkIds(s.content, p, LayoutNode.rootId(p, s.content), false)
          }
      val authored = all.collect { case (id, inSet, Some(name)) =>
        (id, inSet, name)
      }
      val ids = all.map(_._1)

      val shape = authored.flatMap { case (id, inSet, name) =>
        if (inSet)
          List(
            s"node id '$name' is inside a candidate set's clause, which is " +
              "rendered once per member — every member would claim the name"
          )
        else if (!name.matches("[A-Za-z0-9_]+"))
          List(
            s"node id '$name' is not a plain token ([A-Za-z0-9_]+) — ids are " +
              "interpolated into `id` attributes and signal names"
          )
        else if (ids.count(_ == id) > 1)
          List(s"node id '$name' is used more than once")
        else Nil
      }

      shape.distinct
    }

    val chromeErrors: List[String] =
      Option
        .when(
          theme.chrome.nonEmpty && !theme.chrome.contains("id=\"dashboard\"")
        )(
          "theme.chrome must contain an element with id=\"dashboard\" wrapping {{{body}}}"
        )
        .toList

    // The hoist mints `bakeInto`, so a dangling one means the build's and the
    // renderer's id derivations drifted; otherwise the symptom is a blank
    // panel, indistinguishable from an unmatched state group.
    val danglingBakes: List[String] = {
      // Every node under the id the runtime gives it, set members' included:
      // a member's nodes own bake groups too, a slider's heads (#556).
      def nodesIn(
          node: LayoutNode,
          prefix: String,
          id: NodeId,
          within: Option[(NodeId, Int, List[LayoutNode.Step])]
      ): List[(NodeId, LayoutNode, Boolean)] =
        (id, node, within.isDefined) :: (node match {
          case c: LayoutNode.Component =>
            LayoutNode.steps(c.regions).flatMap { case (step, ch) =>
              nodesIn(
                ch,
                prefix,
                LayoutNode.childIdWithin(prefix, id, step, ch, within),
                within.map((m, cl, path) => (m, cl, path :+ step))
              )
            }
          case set: LayoutNode.SetNode =>
            set.members.toList.flatMap { (entityId, m) =>
              val member =
                NodeId.derived(LayoutNode.memberSegment(id, entityId))
              m.clauses.zipWithIndex.flatMap((clause, ci) =>
                nodesIn(clause.node, prefix, member, Some((member, ci, Nil)))
              )
            }
        })
      val walked =
        nodesIn(card, "", LayoutNode.rootId("", card), None) ++
          surfaces.toList.flatMap { case (sid, s) =>
            val p = LayoutNode.surfacePrefix(sid)
            nodesIn(s.content, p, LayoutNode.rootId(p, s.content), None)
          }
      val nodes: Map[NodeId, LayoutNode] =
        walked.map((id, n, _) => id -> n).toMap
      val inMember: Set[NodeId] =
        walked.collect { case (id, _, true) => id }.toSet
      def hostCard(gid: NodeId): Option[CardDef] =
        nodes
          .get(gid)
          .collect { case c: LayoutNode.Component => c.card }
          .flatMap(cards.get)

      surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
        s.bakeInto.toList.flatMap { gid =>
          if (!nodes.contains(gid))
            List(
              s"surface '$sid' bakes into '$gid', which is not a node in this " +
                "dashboard (main tree or any surface's content)"
            )
          // Every clause of a candidate mints the same ids below the member, so
          // only the clause tells two clauses' groups apart (`Surface.group`).
          else if (inMember(gid) && s.clause.isEmpty)
            List(
              s"surface '$sid' bakes into '$gid', inside a candidate set's " +
                "clause, but names no clause"
            )
          else
            // `bakeAs` must be a baked region of that node's card.
            (s.bakeAs, hostCard(gid)) match {
              case (Some(region), Some(cd))
                  if !cd.regions.get(region).exists(_.fill == Region.Baked) =>
                val baked = cd.bakedRegions.keys.toList.sorted
                List(
                  s"surface '$sid' bakes into '$gid' as '$region', but that " +
                    s"node's card declares no baked region '$region'" +
                    (if (baked.isEmpty) " (it declares none)"
                     else s" — it has ${baked.mkString(", ")}")
                )
              // Incoherent: the node owns a bake group while `hostId` falls to
              // the popup host. Also what keeps "a bake owner is structure"
              // true, which the render cache relies on.
              case (None, _) =>
                List(
                  s"surface '$sid' bakes into '$gid' but names no 'bakeAs' — " +
                    "a baked surface must name the region it fills"
                )
              case _ => Nil
            }
        }
      }
    }

    // Selection is per session or server truth per group, never both.
    val activationErrors: List[String] =
      surfaces.toList
        .flatMap { case (sid, s) => s.group.map((_, sid, s.activation)) }
        .groupBy(_._1)
        .toList
        .sortBy(_._1)
        .flatMap { case (gid, members) =>
          val kinds = members.map {
            case (_, _, _: Activation.User)  => "user"
            case (_, _, _: Activation.Var)   => "var"
            case (_, _, _: Activation.State) => "state"
          }.distinct
          val names = members.collect { case (_, _, Activation.Var(n, _)) =>
            n
          }
          // A baked member has a host, and what fills a host is a choice the
          // server knows: a viewer's variable or a condition (ADR 0033).
          val unchosen = members.collect { case (_, sid, _: Activation.User) =>
            sid
          }.sorted
          Option
            .when(unchosen.nonEmpty)(
              s"bake group '$gid' has members nothing selects (" +
                unchosen.mkString(", ") + "): a baked surface is chosen by a " +
                "node variable (activation kind \"var\") or a condition " +
                "(\"state\")"
            )
            .toList ++ Option
            .when(kinds.size > 1 && unchosen.isEmpty)(
              s"bake group '$gid' mixes var- and state-activated members: " +
                members.map(_._2).sorted.mkString(", ")
            )
            .toList ++ names.distinct.match {
            case List(name) =>
              // In [[SurfaceGraph.bakeGroup]]'s order.
              val inOrder = members
                .map(_._2)
                .sorted
                .flatMap(surfaces.get)
                .sortBy(_.bakeIndex.getOrElse(Int.MaxValue))
              varSelectionErrors(
                d,
                gid,
                inOrder.flatMap(_.bakeInto).headOption.getOrElse(gid),
                name,
                inOrder.flatMap(_.selectedAt),
                inOrder.count(_.isOtherwise)
              )
            case Nil  => Nil
            case many =>
              List(
                s"bake group '$gid' is selected by more than one variable: " +
                  many.sorted.mkString(", ")
              )
          }
        }

    val unboundConditions: List[String] =
      surfaces.toList.sortBy(_._1).flatMap { case (sid, s) =>
        s.activation match
          case Activation.State(Some(c)) if conditionError(c).nonEmpty =>
            conditionError(c).toList.map(e =>
              s"surface '$sid' is shown by a condition that does not decide: $e"
            )
          case Activation.State(Some(c)) if c.readsMissingSubject =>
            List(
              s"surface '$sid' is shown by a condition that compares an " +
                "unnamed entity; a state condition must name the entity each " +
                "comparison reads"
            )
          case _ => Nil
      }

    // Without its tally a press would have nothing to filter. `tapRoute` always
    // writes one, so this is a node built some other way.
    val callSetErrors: List[String] =
      declaredCalls.collect {
        case Dashboard.Declared(TapCall(_, CallSet(id), _), card, None) =>
          s"card '$card' places a call over the set '$id' without a " +
            s"readable '${Dashboard.TargetSetSlot}' slot — build its route " +
            "with tapMod.tapRoute"
      }.distinct ++ declaredCalls
        .collect {
          case Dashboard.Declared(
                TapCall(_, CallSet(id), _),
                _,
                Some(t: Value.Present)
              ) =>
            id -> t
        }
        .groupMap(_._1)(_._2)
        .toList
        .sortBy(_._1)
        .collect {
          case (id, ts) if ts.distinct.size > 1 =>
            s"two different sets share the call id '$id'"
        }

    authoredIdErrors ++
      callSetErrors ++
      structureLiveSlotErrors ++
      regionHoleErrors ++
      viewErrors ++
      regionNameErrors ++
      chromeErrors ++
      theme.classes.errors ++
      danglingBakes ++
      activationErrors ++
      unboundConditions ++
      walk(card, "", LayoutNode.rootId("", card), Map.empty) ++
      surfaces.toList.sortBy(_._1).flatMap { case (sid, surface) =>
        val p = LayoutNode.surfacePrefix(sid)
        walk(
          surface.content,
          p,
          LayoutNode.rootId(p, surface.content),
          scopeOf(surface)
        ).map(err => s"surface '$sid': $err")
      }

  /** A clause's or a state surface's condition: a bool that bypasses nothing.
    */
  private def conditionError(e: ValueExpr): Option[String] =
    e.boolError orElse e.bypassError(ownMay = false)

  /** A variable-selected group needs its variable in scope at the host, and a
    * declared value that is a member index: the declared value is what bakes
    * before anyone chooses, and only a viewer's choice is checked at the write.
    */
  private def varSelectionErrors(
      d: Dashboard,
      gid: NodeId,
      host: NodeId,
      name: String,
      values: List[String],
      fallbacks: Int
  ): List[String] =
    import d.*
    Option
      .when(values.distinct.size != values.size)(
        s"bake group '$gid' has two members selected at one value of '$name': " +
          values.mkString(", ")
      )
      .toList ++ Option
      .when(fallbacks > 1)(
        s"bake group '$gid' has $fallbacks members selected at every other " +
          s"value of '$name'; one can be"
      )
      .toList ++ (varScopes.get(host).flatMap(_.get(name)) match {
      case None =>
        List(
          s"bake group '$gid' is selected by the variable '$name', which no " +
            "node at or above it declares"
        )
      case Some(in)
          if fallbacks == 0 && !values.contains(in.declaration.default) =>
        List(
          s"bake group '$gid' is selected by '$name', declared '${in.declaration.default}' " +
            s"on ${in.declarer}, which is not ${Surface.membersText(values)}"
        )
      case Some(_) => Nil
    })
