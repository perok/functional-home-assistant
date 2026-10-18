# Plan: node variables inside a candidate set's clause (#556)

## What changes for an author

```pkl
q.from(dump.lights.all).caseOf(q.eq(q.stateProp, "on"), c.entitySlider)
```

Each light's slider keeps its own main control (brightness, colour temperature, colour),
switched on its own. Today a slider rendered per member keeps its first control, because the
build refuses a declaration inside a clause.

## Decided (2026-10-18)

- **Per member.** Each member's copy of a clause declares its own variable.
- **The server keeps a member's last choice while it is away.** A member's declarer id is
  fixed at build time (the candidates are static, ADR 0003), so its choice stays in the
  session under that id. When the member comes back, it shows what was last chosen.
- **The URL entry goes with the member**, by the mechanism already built: a declarer's
  `data-fh-url` releases its parameter when its element leaves the page.

## How

- `Dashboard.varScopes` walks each member's clause nodes under the member's id
  (`LayoutNode.memberSegment`, the id `MemberGraph` mints and the build's hoist uses), starting
  from the set's scope. Every clause of a candidate shares that id, so their scopes are merged:
  a declaration in one clause survives the member moving to another.
- That makes `VarGraph`'s `setOfMember` fallback redundant: a member now has its own entry,
  the set's scope plus its declarations. It goes, so there is one mechanism.
- Surfaces baked into a member's nodes (a slider's control heads) now find their host's scope.
- `validate` drops the refusal; a clause node's readers resolve against its own declarations.
- The slider's `perMember` workaround (`Node.perMember`, keep the first control) goes, and the
  flag with it.

## Tests

- Through the server: two lights in a set, each with its own control. A write to one member's
  variable moves only that member's slider. A member leaving and returning (its state flips
  out of the clause and back) shows the choice made before it left.
- `validate`: a declaration in a clause builds.
- Existing tests pinning the refusal and the first-control workaround are rewritten (approved
  2026-10-18).

## Docs touched

ADR 0033 (scopes, the per-member section), ADR 0003 if it states that a clause cannot declare,
the rendering pipeline's variable section, the module CLAUDE.md's slider paragraph.
