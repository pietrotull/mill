package mill.define

import ammonite.main.Router.Overrides
import ammonite.ops.Path

object BaseModule{
  case class Implicit(value: BaseModule)
}
class BaseModule(millSourcePath0: Path, external0: Boolean = false)
                (implicit millModuleEnclosing0: sourcecode.Enclosing,
                 millModuleLine0: sourcecode.Line,
                 millName0: sourcecode.Name)
  extends Module()(
    mill.define.Ctx.make(
      implicitly,
      implicitly,
      implicitly,
      BasePath(millSourcePath0),
      Segments(),
      Overrides(0),
      Ctx.External(external0)
    )
  ){
  // A BaseModule should provide an empty Segments list to it's children, since
  // it is the root of the module tree, and thus must not include it's own
  // sourcecode.Name as part of the list,
  override implicit def millModuleSegments: Segments = Segments()
  override def millSourcePath = millOuterCtx.millSourcePath
  override implicit def millModuleBasePath: BasePath = BasePath(millSourcePath)
  implicit def millImplicitBaseModule: BaseModule.Implicit = BaseModule.Implicit(this)
}


abstract class ExternalModule(implicit millModuleEnclosing0: sourcecode.Enclosing,
                              millModuleLine0: sourcecode.Line,
                              millName0: sourcecode.Name)
  extends BaseModule(ammonite.ops.pwd, external0 = true){
  def millDiscover: Discover[_]
  implicit def millDiscoverImplicit: Discover[_] = millDiscover
  assert(
    !" #".exists(millModuleEnclosing0.value.contains(_)),
    "External modules must be at a top-level static path, not " + millModuleEnclosing0.value
  )
  override implicit def millModuleSegments = {
    Segments(millModuleEnclosing0.value.split('.').map(Segment.Label):_*)
  }
}