# Plan — a header, a footer and space that fills

A row that sits at the bottom of the page as a footer, or at the top as a header, and a child
that takes whatever space its row or column leaves. Discussed in chat on 2026-10-17.

```pkl
(c.column) {
  children {
    header.stick("top")
    body.grow()
    footer.stick("bottom")
  }
}.fillScreen()

(c.row) { children { title; c.spacer; c.button("Settings", …) } }   // pushes the button right
```

## Properties, not a footer component

Being a footer is a position, not a kind of content: the same row may be a footer on a phone and
plain content elsewhere. The library already splits layout two ways, and these follow it:

- **How one child sits** is a builder on any node, a class on its cell: `.hug()`,
  `.columns(n)`, and now `.grow()` and `.stick(edge)`.
- **How a container spreads its children** is on the container: `.justify(…)`, `.wrap()`, and now
  `Column.fillScreen()`.

## The pieces

- **`.grow()`**: the cell takes the space its row or column leaves (`flex: 1 1 auto`). In a
  column under `fillScreen()` it is the body between a header and a footer; in a wrapped row it
  fills the rest of the line. `.grow()` with `.hug()` on one node is a build error in either
  order, since they ask for opposite things.
- **`c.spacer`**: an empty node that grows. Space that pushes its siblings apart, with no wrapper
  around them.
- **`.stick("top" | "bottom")`**: the cell stays on screen while the page scrolls
  (`position: sticky`). Left off, a footer scrolls away and only sits at the bottom when the page
  is short. The two combine.
  - It sticks within its parent box. A direct child of the page's main column is always on
    screen; deeper down, it sticks only while its parent is.
  - It is painted on the page's own background, a new `--fh-page` token (HA's
    `--primary-background-color`; BeerCSS's `--surface`), so content does not show through.
  - BeerCSS's `main` is `overflow: clip`, which is not a scroll container, so sticky passes
    through it.
- **`Column.fillScreen()`**: the column is at least as tall as the screen, less the theme's own
  frame around the dashboard: `calc(100dvh - var(--fh-chrome-block, 1rem))`, after a `100vh`
  line for iPadOS 15.0–15.3, which has no `dvh`. `1rem` is both themes' frame: BeerCSS's `main`
  padding, and the plain theme's default body margin. A theme with more frame (an app bar) sets
  the token. It is meant for a dashboard's root column.

## Fixed first: containers inherit their parent's child layout

A container states its children's layout as inherited custom properties (`--fh-cell-flex`,
`--fh-cell-width`, `--fh-cell-align-self`, `core/css.pkl`), so a container that does not set one
passes its PARENT's value on to its own cells. Two do not:

- **A column sets only the width.** Inside a row its cells inherit `flex: 1 1 0`. Harmless while
  a column has no height of its own; under `fillScreen()` every child would split the height
  evenly, header and footer included.
- **A grid does not set `align-self`.** Inside a row its cells inherit `flex-start`, so cards on
  one line do not stretch to the same height as they do in a grid at the root.

The rule becomes: every container sets all three. A column at the root sets the values its cells
already default to, so nothing moves there; only a column or grid nested in a row changes.

## Not in this change

- A dashboard-wide default for sticking, like `entry.pkl`'s `textOverflow`, and sticking only
  at phone width. Both are small to add later.
- A dropdown in a bottom-stuck footer opens downward, off the screen.
- The iPhone home bar: `env(safe-area-inset-bottom)` is zero until the page asks for
  `viewport-fit=cover`, which it does not.

## Tests

- Pkl facts (`layout.test.pkl`):
  - each builder's classes, and a CSS rule for each;
  - `.grow()` with `.hug()` is a build error in both orders;
  - **the property behind the fix**: every container sets every custom property a cell reads.
    The properties come from the cell rule itself and the containers from the layout module's
    classes, so a new property or a new container cannot be forgotten.
- Smoke, in a browser (CI only, since the sandbox has no working Chromium):
  - a short page puts the footer at the bottom of the screen;
  - on a long page, scrolling keeps the header at the top and the footer at the bottom.

## Docs in the same change

`core/css.pkl`'s layout contract, ADR 0008 (the cell builders), `docs/terminology.md`'s cell
entry, the module `CLAUDE.md`, and the header of `layout.pkl`.
