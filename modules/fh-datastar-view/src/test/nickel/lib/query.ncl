# Two builder styles over one shared fold, in separate namespaces so both can be
# driven side by side — including in the editor: type `q.pipe.` vs `q.builder.`.
{
  expr = import "query/expr.ncl",
  pipe = import "query/pipe.ncl",
  builder = import "query/builder.ncl",
}
