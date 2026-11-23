package fh.view.model

import fh.view.runtime.{Cel, Steps, Templates}

/** What `Dashboard.validate` checks: every rule across fields or nodes, as
  * human-readable errors. A rule about one value is its type's, at decode.
  */
private[model] object Validate:

  def errors(
      d: Dashboard,
      locateTransform: String => Option[String],
      compiled: Map[String, Either[String, Templates.Card]],
      label: Either[String, Templates.Card]
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
          val undeclared = (filled -- cd.regions).toList.sorted.map(r =>
            s"$nodeId: fills region '$r', which card '$cardName' does not " +
              "declare — its children would never render"
          ) ++ slotNames
            .intersect(cd.regions)
            .toList
            .sorted
            .map(r =>
              s"$nodeId: slot '$r' is named like card '$cardName''s region, " +
                "whose children its hole shows instead — rename the slot"
            )
          val names = cardReads.get(cardName).flatMap(_.toOption)
          val known = slotNames ++ Dashboard.injectedStatic ++
            cd.regions
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
            src.inputs.values.flatMap(_.bypassError(ownMay = false))).headOption
            .map(e => s"$nodeId: slot '$name': $e")
            .toList
        stepErrors ++ bypassErrors ++ signalErrors(
          nodeId,
          cardName,
          key,
          src
        ) ++
          readErrors(nodeId, cardName, name, src) ++
          fetchErrors(nodeId, name, src, scope) ++
          holeErrors(nodeId, cardName, name, src) ++
          expressionErrors(nodeId, cardName, name, src) ++
          src.allInputs
            .map(_.source)
            .collect { case Value.Fetch(f) =>
              askErrors(nodeId, name, f, scope)
            }
            .flatten
      }

    /** A chosen window's `by` ([[chosenErrors]]); every window it could pick is
      * one HA answers. A viewer's value is checked at the write.
      */
    def askErrors(
        nodeId: String,
        name: String,
        template: FetchTemplate,
        scope: Map[String, String]
    ): List[String] =
      val at = s"$nodeId: slot '$name'"
      val problems = template.window.all
        .flatMap(_.problem)
        .distinct
        .map(p => s"$at $p")
      template.window match
        case Choice.Fixed(_)     => problems
        case c: Choice.Chosen[?] =>
          chosenErrors(at, "its window", "the window", c, scope) ++ problems

    /** What a `Chosen` holds wherever it is: its `by` reads only variables
      * declared above it, is a key a case can spell, never a fetch, and decides
      * a case at its declared values where it reads no state. `what` and `the`
      * name what it picks, for the message.
      */
    def chosenErrors(
        at: String,
        what: String,
        the: String,
        c: Choice.Chosen[?],
        scope: Map[String, String]
    ): List[String] =
      val Choice.Chosen(by, cases, _) = c
      val refErrors = by.variables.sorted
        .filterNot(scope.contains)
        .map(v =>
          s"$at reads the variable '$v', which no ancestor declares — " +
            "declare it on the node that owns the choice, or write the " +
            "value down"
        )
      val keyType = by.typed(Nil) match
        case Left(m) =>
          Left(
            s"$at chooses $what by a value that does not type: ${m.message}"
          )
        case Right(t) if Choice.KeyTypes(t.out) => Right(t.out)
        case Right(t)                           =>
          Left(
            s"$at chooses $what by a ${t.out.name}, which no case " +
              "can spell — text, a bool or a whole number"
          )
      def shown(l: Lit) = Steps.text(l.raw)
      val caseErrors = keyType.fold(
        List(_),
        t =>
          cases.collect {
            case k if ValueType.of(k.is) != t =>
              s"$at has the case '${shown(k.is)}', a " +
                s"${ValueType.of(k.is).name}, where $what is chosen by " +
                s"a ${t.name}"
          }
      ) ++ cases
        .groupBy(k => Lit.key(k.is))
        .collect { case (_, ks) if ks.sizeIs > 1 => shown(ks.head.is) }
        .toList
        .sorted
        .map(k => s"$at has the case '$k' twice")
      val fetchErrors = Option
        .when(by.fetches.nonEmpty)(
          s"$at chooses $what by a fetch, whose answer could only be " +
            s"read once $the is chosen"
        )
        .toList
      val declared =
        if (refErrors.nonEmpty || keyType.isLeft || !c.refusesUntaken) Nil
        else
          val read = FetchTemplate.atDeclared(scope)(by)
          val reads =
            if (by.variables.isEmpty)
              s"chooses $what by a value no case takes"
            else
              s"reads the variable '${by.variables.mkString(", ")}' at " +
                "its declared value"
          Option
            .unless(c.takes(read))(
              s"$at $reads: '${Steps.text(read)}' is not one of " +
                cases.map(k => shown(k.is)).sorted.mkString(", ")
            )
            .toList
      refErrors ++ caseErrors ++ fetchErrors ++ declared

    /** Otherwise a bad fetch renders blank forever with nothing saying why. A
      * fetch slot's value is its stage's bytes, so a step after the stage would
      * never run; `jsonText` is the one that need not.
      */
    def fetchErrors(
        nodeId: String,
        name: String,
        src: BoundSlot,
        scope: Map[String, String]
    ): List[String] =
      src.fetch.toList.flatMap { template =>
        val stageError = src.expr.steps match
          case Nil => None
          case (_: Step.FetchStage) :: rest
              if rest.forall(_ == Step.JsonText) =>
            None
          case _ =>
            Some(
              s"$nodeId: slot '$name' reads a fetch, so its steps are a " +
                "stage (json, then jsonText; or a chart) — for CEL over it, " +
                "name the fetch as an input of a slot that reads state"
            )
        askErrors(nodeId, name, template, scope) ++ stageError.toList
      }

    /** Which holes a slot's type allows: escaping is removed only where a card
      * declares it.
      */
    /** Text pasted into JavaScript by an escaping hole: HTML-escaped, never
      * JS-quoted, so a `'` or a newline in it breaks the handler (point 4). A
      * `jsonText` step's output is JSON, already a JavaScript literal.
      */
    def expressionErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot
    ): List[String] =
      Option
        .when(
          (src.`type` == SlotType.Text || src.`type` == SlotType.TextRaw) &&
            !src.expr.steps.lastOption.contains(Step.JsonText) &&
            cardReads
              .get(cardName)
              .flatMap(_.toOption)
              .exists(_.inExpression(name))
        )(
          s"$nodeId: card '$cardName' places text slot '$name' as {{$name}} " +
            s"inside a Datastar expression, where it is pasted into JavaScript " +
            s"— place {{{$name.jsValue}}}, or make it a number or bool slot"
        )
        .toList

    def holeErrors(
        nodeId: String,
        cardName: String,
        name: String,
        src: BoundSlot
    ): List[String] =
      cardReads.get(cardName).flatMap(_.toOption).toList.flatMap { reads =>
        val wrong = src.`type` match
          case SlotType.Text | SlotType.Number =>
            Option.when(reads.raw(name))(s"{{{$name}}}")
          case SlotType.TextRaw =>
            Option.when(reads.escaped(name))(s"{{$name}}")
          case SlotType.Bool =>
            Option
              .when(reads.raw(name))(s"{{{$name}}}")
              .orElse(Option.when(reads.escaped(name))(s"{{$name}}"))
        wrong.map(hole =>
          s"$nodeId: card '$cardName' places slot '$name' as $hole, but the " +
            s"slot is ${src.`type`.wire}, which is placed as " +
            (src.`type` match
              case SlotType.Text | SlotType.Number => s"{{$name}}"
              case SlotType.TextRaw                => s"{{{$name}}}"
              case SlotType.Bool => s"a section, {{#$name}}") +
            " — declare the type the card means"
        )
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
              "input has no subject"
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
      val read = c.bound.values.flatMap(_.inputs.keySet).toSet
      c.inputs.keys.toList.sorted.flatMap { name =>
        if (!Dashboard.InputName.matches(name))
          List(
            s"$nodeId: input '$name' is not a name an expression " +
              "can read — letters, digits and '_', not starting with a digit"
          )
        else if (Cel.fixedNames.contains(name))
          List(
            s"$nodeId: input '$name' would hide the '$name' every " +
              "expression already reads — rename it"
          )
        else if (!read.contains(name))
          List(
            s"$nodeId: input '$name' is read by no expression on " +
              "this node — names are the node's own and are not inherited"
          )
        else
          // A value is shared by content across nodes, so it has no subject.
          val v = c.inputs(name)
          v.unnamedComparison(
            s"$nodeId: input '$name'",
            v.typed(Nil)
          ).fold(inputErrors(nodeId, s"input '$name'", v, Nil))(
            List(_)
          )
      } ++ (
        labelled(c.bound).map((_, n, s) =>
          (s"slot '$n'", s.expr, ValueExpr.env(c.inputs))
        ) ++ c.inputs.toList
          .sortBy(_._1)
          .map((n, v) => (s"input '$n'", v, Nil))
      ).flatMap { (where, expr, env) =>
        // Each CEL's own at any depth, read by that CEL; the node's are in
        // scope beside, but not inside a guard or an order term.
        expr.scoped.flatMap { (e, nodeScope) =>
          e.cels.flatMap { (cel, own) =>
            val read = Cel.identifiers(cel)
            own.keys.toList.sorted.flatMap { name =>
              if (
                !Dashboard.InputName.matches(name) ||
                Cel.fixedNames.contains(name)
              )
                List(
                  s"$nodeId: $where has an input '$name' its " +
                    "expression cannot read — letters, digits and '_', and not " +
                    s"one of ${Cel.fixedNames.toList.sorted.mkString(", ")}"
                )
              else if (!read.contains(name))
                List(
                  s"$nodeId: $where has an input '$name' its " +
                    "expression does not read"
                )
              else
                inputErrors(
                  nodeId,
                  s"$where's input '$name'",
                  own(name),
                  if (nodeScope) env else Nil
                )
            }
          }
        }
      } ++ labelled(c.bound).map((_, n, s) => (n, s)).collect {
        // The once-cache keys by what a value IS, not by what it counts now.
        case (slot, src)
            if src.reads == Reads.Once && src.allInputs.exists(_.isLive) =>
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

    /** A variable's value is the viewer's, so it is read only where bytes are
      * rendered per viewer: a leaf's value slot. A signal is named by what it
      * reads and shared by every viewer, and structure is never re-rendered.
      * The exception is `v.valueIs(x)` in a bool signal, on any card, which the
      * browser reads off the variable's own signals. A decision's are
      * [[decisionVarErrors]]'.
      */
    def slotVarErrors(
        nodeId: String,
        cardName: String,
        slots: Map[SlotKey, BoundSlot],
        scope: Map[String, String]
    ): List[String] =
      val structural = cards.get(cardName).exists(_.isStructure)
      labelled(slots).flatMap { (_, name, s) =>
        val read = (s.expr.variables ++ s.inputs.values.flatMap(
          _.variables
        )).distinct.sorted
        val where = read.map(v => s"'$v'").mkString(", ")
        read
          .filterNot(scope.contains)
          .map(v =>
            s"$nodeId: slot '$name' reads the variable '$v', which no " +
              "ancestor declares — declare it on the node that owns the choice"
          ) ++ (
          if (read.isEmpty || s.chosenSignal.isDefined) None
          else if (structural)
            Some(
              s"$nodeId: slot '$name' reads the variable $where, but card " +
                s"'$cardName' holds regions, so its bytes are never re-rendered " +
                "for a viewer — read it in a leaf below it"
            )
          else if (s.signal.isDefined)
            Some(
              s"$nodeId: slot '$name' reads the variable $where, but it is a " +
                "signal, which every viewer shares — read it in a slot that " +
                "is not a signal, or as `v.valueIs(x)` in a bool one"
            )
          else None
        )
      }

    def valueWhileErrors(
        nodeId: String,
        slots: Map[SlotKey, BoundSlot]
    ): List[String] =
      labelled(slots).flatMap { (_, name, src) =>
        src.valueWhile.toList.flatMap { w =>
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

    def typeErrors(
        nodeId: String,
        slots: Map[SlotKey, BoundSlot]
    ): List[String] =
      // An attribute is `dyn` to CEL, but read bare it is text or a number far
      // more often than a bool.
      def bareAttr(src: BoundSlot) = (src.expr.source, src.expr.steps) match
        case (Value.Entity(_, _: Part.Attr, _, _), Nil) => true
        case _                                          => false
      def hint(out: ValueType, to: SlotType) = out match
        case _ if to == SlotType.Number =>
          "make it a number, `double(…)` or `int(…)`"
        case ValueType.Json             => "place it with a jsonText step"
        case ValueType.TextRaw          => "declare the slot textRaw"
        case o if o == ValueType.Series =>
          "give it a stage: json then jsonText, or a chart"
        case _ => "make it text, `str(…)` or `string(…)`"
      labelled(slots).flatMap { case (_, name, src) =>
        src.typed match
          case Left(ValueExpr.Mistyped(err, Some(t))) =>
            val at = locateTransform(t).fold("")(loc => s" (at $loc)")
            List(s"$nodeId: slot '$name' has an invalid transform$at: $err")
          case Left(ValueExpr.Mistyped(err, None)) =>
            List(s"$nodeId: slot '$name' $err")
          // Every literal fits a text slot.
          case Right(t) if src.constant.isDefined =>
            Option
              .unless(ValueType.fits(t.out, src.`type`))(
                s"$nodeId: slot '$name' holds a ${t.out.name} literal, " +
                  s"which a ${src.`type`.wire} slot does not take"
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
                  s"${src.`type`.wire} slot does not take — ${hint(t.out, src.`type`)}"
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
            s"slot's (${src.`type`.wire})"
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

    def placedCallErrors(
        nodeId: String,
        c: LayoutNode.Component
    ): List[String] =
      c.calls.keySet
        .intersect(c.valueSlots.keySet)
        .toList
        .sorted
        .map(n => s"$nodeId: '$n' is both a slot and a call — rename one")

    /** A nodes slot's choice: keys typed by `by`, one node per case. A `by`
      * reading variables and state decides per viewer, and a value no case
      * takes shows nothing; only the plain variable's declared value is held to
      * a case, since it would show nothing before anyone chooses.
      */
    def choiceFillErrors(
        nodeId: String,
        c: LayoutNode.Component,
        scope: Map[String, String]
    ): List[String] =
      c.chosen.toList.sortBy(_._1).flatMap { (n, k) =>
        val at = s"$nodeId: nodes slot '$n'"
        val nodes = k.cases.map(_.value) ++ k.otherwise
        val lengths = nodes
          .filter(_.sizeIs != 1)
          .map(_ =>
            s"$at has a case that is not exactly one node (several go in a " +
              "row)"
          )
          .distinct
        val unshown = Choice.plainVariable(k.by) match
          case Some(v) if k.otherwise.isEmpty =>
            scope
              .get(v)
              .filterNot(d => k.cases.exists(_.is == Lit.Text(d)))
              .map(d =>
                s"$at is chosen by '$v', declared '$d', which no case takes " +
                  s"(${k.cases.map(c => Steps.text(c.is.raw)).mkString(", ")}): " +
                  "it would show nothing until a viewer chooses"
              )
              .toList
          case _ => Nil
        chosenErrors(at, "its nodes", "its nodes", k, scope) ++ lengths ++
          unshown
      }

    /** A choice is of the variable as this node reads it. */
    def choiceErrors(
        nodeId: String,
        c: LayoutNode.Component,
        scope: Map[String, String]
    ): List[String] =
      c.calls.toList.sortBy(_._1).collect {
        case (n, CallDecl.SetVar(v, _)) if !scope.contains(v) =>
          s"$nodeId: '$n' chooses the variable '$v', which no node at or " +
            "above it declares"
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

    def childErrors(ix: Ix, scope: Map[String, String]): List[String] =
      index.children(ix).filter(index.step(_).isDefined).flatMap(walk(_, scope))

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
        .flatMap(c => c.layout ++ c.classes.keys)
        .flatMap(classTokenErrors(nodeId, _)) ++
        cell.toList.flatMap(_.styles.collect {
          case (p, s) if s.constant.isDefined =>
            s"$nodeId: cellStyle '$p' must read live state — anything else " +
              "is never bound"
        })

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

    def walk(ix: Ix, scope: Map[String, String]): List[String] =
      val nodeId = index.id(ix)
      index.node(ix) match
        case c @ LayoutNode.Component(card, _, cell, _, vars, _, _) =>
          val layoutErrors = Option
            .when(
              cards.get(card).exists(!_.layoutCell) &&
                cell.exists(_.layout.nonEmpty)
            )(
              s"$nodeId: card '$card' has layoutCell=false, so its root is " +
                "no layout box — drop its layout classes " +
                cell.toList.flatMap(_.layout).mkString(", ")
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
            c.valueSlots.keySet ++ c.calls.keySet,
            c.fills.keySet
          ) ++ placedCallErrors(nodeId, c) ++
            choiceFillErrors(nodeId, c, here) ++
            choiceErrors(nodeId, c, here) ++
            slotErrors(nodeId, card, checked, here) ++
            varErrors(nodeId, vars) ++
            slotVarErrors(nodeId, card, c.bound, here) ++
            valueErrors(nodeId, c) ++
            valueWhileErrors(nodeId, c.bound) ++
            typeErrors(nodeId, checked) ++
            cellErrors(nodeId, cell) ++
            layoutErrors ++ childErrors(ix, here) ++ setErrors(ix, c, scope)

    // A set's members are complete nodes, validated as ordinary ones.
    def setErrors(
        ix: Ix,
        c: LayoutNode.Component,
        scope: Map[String, String]
    ): List[String] =
      val setId = index.id(ix)
      val sets = c.fills.toList.sortBy(_._1).collect { case (n, s: Fill.Set) =>
        n -> s
      }
      // The card's root is the element a choice's case is patched into.
      val choices = c.fills.toList.sortBy(_._1).collect {
        case (n, Fill.Nodes(_: Choice.Chosen[?]))
            if cards.get(c.card).exists(_.regions != Set(n)) =>
          s"$setId: the choice in '$n' is not its card's whole template — " +
            "its case is patched into the card's root, so put the choice in " +
            "a group of its own"
      }
      choices ++ sets.flatMap { (region, s) =>
        val whole =
          Option
            .when(
              sets.size > 1 || cards
                .get(c.card)
                .exists(_.regions != Set(region))
            )(
              s"$setId: the set in '$region' is not its card's whole " +
                "template — members are placed relative to each other, so " +
                "a fixed sibling in the same element would need anchors"
            )
            .toList
        val order = s.orderBy
          .flatMap(t =>
            t.by.typed(Nil).left.toOption.map(_.message) orElse
              t.by.bypassError(ownMay = false)
          )
          .map(e => s"$setId: an order term cannot be read: $e")
        val members = s.members.toList.sortBy(_._1).flatMap { (candidate, m) =>
          m match
            case Choice.Fixed(_)                     => Nil
            case Choice.Chosen(by, cases, otherwise) =>
              // A chain's items are the conditions; its own type is the key's.
              val guards = by match
                case ValueExpr(
                      Value.List(_, items),
                      List(Step.IndexOfTrue),
                      _,
                      _
                    ) =>
                  items.map(conditionError)
                case single => List(conditionError(single))
              val keyed = by.typed(Nil) match
                case Right(t) if Choice.KeyTypes(t.out) =>
                  cases.collect {
                    case k if ValueType.of(k.is) != t.out =>
                      s"$setId: a case of '$candidate' is a " +
                        s"${ValueType.of(k.is).name}, where its guard is a " +
                        s"${t.out.name}"
                  }
                case _ => Nil
              guards.flatten
                .map(e =>
                  s"$setId: a clause of '$candidate' does not decide: $e"
                ) ++
                keyed ++
                otherwise.map(_ =>
                  s"$setId: '$candidate' has an otherwise, which a set's " +
                    "member does not take"
                ) ++
                cases
                  .groupBy(k => Lit.key(k.is))
                  .collect { case (_, ks) if ks.sizeIs > 1 => ks.head }
                  .map(k =>
                    s"$setId: '$candidate' has the case " +
                      s"'${Steps.text(k.is.raw)}' twice"
                  )
        }
        val unmembered = s.candidates.filterNot(s.members.contains).map { cd =>
          s"$setId: candidate '$cd' has no member entry — it could never " +
            "render, so the build dropped it inconsistently"
        }
        val readsMember = Option
          .when(
            s.of != IdKind.Entity && (
              s.members.values.exists {
                case Choice.Chosen(by, _, _) => by.readsMissingSubject
                case Choice.Fixed(_)         => false
              } || s.orderBy.exists(_.by.readsMissingSubject)
            )
          )(
            s"$setId: a set of ${s.of.word}s reads its member, which has " +
              "no state — name the entity a guard or an order term reads"
          )
          .toList
        // Such a member's root and its inner set would share one id
        // (`innerSetId(member, clause, Nil)`).
        val bare = s.nodes.collect {
          case n: LayoutNode.Component if n.set.isDefined =>
            s"$setId: a member is itself a set — put the set inside a card " +
              "the member renders"
        }.distinct
        whole ++ order ++ members ++ unmembered ++ readsMember ++ bare
      } ++ index
        .children(ix)
        .filter(index.step(_).isEmpty)
        .flatMap(walk(_, scope))

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
          here ++ c.allChildren.flatMap(walk) ++
            c.set.toList.flatMap((_, s) => s.nodes.flatMap(walk))
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
            cd.regions.toList.sorted.flatMap { r =>
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
                      s"card '$name': declares region '$r' " +
                        s"but its `template` places no $hole hole for it — " +
                        "nothing would ever appear there"
                    )
                  case 1 => Nil
                  case n =>
                    List(
                      s"card '$name': places region '$r' $n times — its " +
                        "children would render twice under the same ids"
                    )
            } ++ CardDef.RootHoles.toList.sorted.flatMap { h =>
              val hole = cd.holeOf(h)
              Option
                .when(
                  reads.misplacedRegions(h) ||
                    reads.regionHoles.getOrElse(h, 0) != 1
                )(
                  s"card '$name': its root must place $hole exactly once, " +
                    "outside every section — the Pkl library writes the " +
                    "root (ADR 0008), so a node's id cannot go missing"
                )
                .toList
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
        cd.regions.toList.sorted.collect {
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
      * descendant. Containment is the index's ([[TreeIndex.contains]]), which
      * asks the tree, so `detail` and `detail_0` may be unrelated.
      */
    val authoredIdErrors: List[String] = {
      val all = index.all.toList.map(ix =>
        (index.id(ix), index.within(ix).isDefined, index.node(ix).authoredId)
      )
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

    // Given a tone and a text, nothing else: any other name would print empty.
    val failedLabelErrors: List[String] = label.fold(
      msg => List(s"failedLabel does not compile: $msg"),
      card =>
        (card.reads.names -- Dashboard.FailedLabelHoles).toList.sorted.map(n =>
          s"failedLabel reads '$n', but a failed read gives it only " +
            Dashboard.FailedLabelHoles.toList.sorted.mkString(" and ")
        )
    )

    // A choice is decided about no subject, over state, variables or both.
    val unboundConditions: List[String] =
      picks.toList.sortBy(_._1).flatMap { (gid, p) =>
        val by = p.choice.by
        by.bypassError(ownMay = false)
          .map(e =>
            s"the choice at '$gid' is decided by a value that does not decide: $e"
          )
          .orElse(
            Option.when(by.readsMissingSubject)(
              s"the choice at '$gid' compares an unnamed entity; a choice " +
                "over state must name the entity each comparison reads"
            )
          )
          .toList
      }

    // A call runs at the press as a decision: for no viewer, about no subject.
    val callErrors: List[String] =
      calls.toList
        .sortBy(_._1)
        .collect { case (_, d: CallDecl.Service) =>
          d
        }
        .flatMap { d =>
          val call = d.named
          // The kind of id a field names, which HA takes as text or a list of it.
          val fields =
            ("service", d.service, None) ::
              d.target.toList.flatMap((k, es) => es.map((k.key, _, Some(k)))) ++
              d.exclude.toList
                .flatMap((k, es) =>
                  es.map((s"exclude.${k.key}", _, Some(k)))
                ) ++
              d.data.toList.map((k, e) => (s"data.$k", e, None))
          val fieldErrors = fields.flatMap { (where, e, ids) =>
            val typed = e.typed(Nil)
            List(
              typed.left.toOption.map(m => s"$call: $where: ${m.message}"),
              Option.when(e.readsMissingSubject)(
                s"$call: $where reads an entity it does not name — a call has " +
                  "no subject, so name it"
              ),
              e.variables.headOption.map(v =>
                s"$call: $where reads the node variable '$v' — a call is " +
                  "decided once for every viewer"
              ),
              typed.toOption
                .map(_.out)
                .filter { out =>
                  if (where == "service") !ValueType.fits(out, SlotType.Text)
                  else if (ids.isDefined)
                    out != ValueType.Text && out != ValueType
                      .List(ValueType.Text)
                  else false
                }
                .map(out => s"$call: $where gives a ${out.name}"),
              (ids, e.source) match
                case (Some(k), p: Value.Present) if p.of != k =>
                  Some(
                    s"$call: $where holds a set of ${p.of.word} ids, which goes " +
                      s"under ${p.of.key}"
                  )
                case _ => None
            ).flatten
          }
          val dataKeyErrors = d.data.keys.toList
            .filter(k => IdKind.ofKey(k).isDefined || k == "exclude")
            .map(k =>
              s"$call: the data key '$k' is named like a target key, and HA " +
                "merges a call's target into its data"
            )
          fieldErrors ++ dataKeyErrors
        }

    // A decision's reads are what it asks every version, so its window is
    // written down: one chosen by state would be a pick per version.
    val decisionFetchErrors: List[String] =
      decisionFetches.flatMap((where, f) =>
        f.window match
          case Choice.Chosen(by, _, _) if by.variables.nonEmpty =>
            by.variables.map(v =>
              s"$where reads the fetch '${f.key}': a decision is made for " +
                s"every viewer at once, so its fetch cannot read the variable '$v'"
            )
          case _: Choice.Chosen[?] =>
            List(
              s"$where reads the fetch '${f.key}', whose window is chosen: a " +
                "decision's window is written down"
            )
          case Choice.Fixed(_) => Nil
      )

    val decisionVarErrors: List[String] =
      decisionExprs
        .flatMap((where, e) =>
          e.variables.map(v =>
            s"$where reads the variable '$v': a decision is made for every " +
              "viewer at once, so it cannot read a viewer's value — a " +
              "variable chooses a branch through c.iff(v.valueIs(x))"
          )
        )
        .distinct

    authoredIdErrors ++
      callErrors ++
      decisionFetchErrors ++
      decisionVarErrors ++
      structureLiveSlotErrors ++
      regionHoleErrors ++
      viewErrors ++
      regionNameErrors ++
      chromeErrors ++
      failedLabelErrors ++
      theme.classes.errors ++
      unboundConditions ++
      index.roots.flatMap { root =>
        val errs = walk(root, Map.empty)
        val sid = index.tree(root)
        if (sid.isEmpty) errs else errs.map(err => s"surface '$sid': $err")
      }

  /** A clause's condition: a bool that bypasses nothing. */
  private def conditionError(e: ValueExpr): Option[String] =
    e.boolError orElse e.bypassError(ownMay = false)
