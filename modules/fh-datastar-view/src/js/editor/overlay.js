// Edit-mode overlay for the dashboard preview: hovering a live node
// (.fh-cell[id]) reveals a Focus/Debug toolbar. Focus outlines it and messages
// the parent editor; Debug fetches the node's live entity data into a tooltip.
// Injected into the dashboard page only under ?edit=1, reading window.__FH_EDIT__.
(function () {
  const cfg = window.__FH_EDIT__
  if (!cfg) return

  const bar = document.createElement("div")
  bar.id = "fh-edit-bar"
  bar.innerHTML = '<button data-a="focus">focus</button><button data-a="debug">debug</button>'
  const tip = document.createElement("pre")
  tip.id = "fh-edit-tip"
  document.body.appendChild(bar)
  document.body.appendChild(tip)

  let cur = null
  function anchor(el) {
    const r = el.getBoundingClientRect()
    bar.style.left = (r.left + scrollX) + "px"
    bar.style.top = (r.top + scrollY) + "px"
    bar.style.display = "flex"
  }

  document.addEventListener("mouseover", (e) => {
    const cell = e.target.closest(".fh-cell[id]")
    if (!cell || cell === cur) return
    if (cur) cur.classList.remove("fh-edit-hl")
    cur = cell
    cell.classList.add("fh-edit-hl")
    anchor(cell)
  })

  bar.addEventListener("mousedown", (e) => e.preventDefault())
  bar.addEventListener("click", (e) => {
    const a = e.target.getAttribute && e.target.getAttribute("data-a")
    if (!a || !cur) return
    const id = cur.id
    if (a === "focus") {
      try { parent.postMessage({ type: "fh-focus", nodeId: id, slug: cfg.slug }, "*") } catch (_) {}
    } else if (a === "debug") {
      fetch(cfg.base + "edit/node/" + cfg.slug + "/" + encodeURIComponent(id) + "/debug")
        .then((r) => r.json())
        .then((d) => {
          tip.textContent = (d && d.length) ? JSON.stringify(d, null, 2) : "(no entities bound to " + id + ")"
          const r = cur.getBoundingClientRect()
          tip.style.left = (r.left + scrollX) + "px"
          tip.style.top = (r.bottom + scrollY + 4) + "px"
          tip.style.display = "block"
        })
        .catch((err) => { tip.textContent = "debug error: " + err; tip.style.display = "block" })
    }
  })

  document.addEventListener("click", (e) => {
    if (e.target.closest("#fh-edit-bar") || e.target.closest("#fh-edit-tip")) return
    tip.style.display = "none"
  })
})()
