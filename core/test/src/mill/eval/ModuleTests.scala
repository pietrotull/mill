package mill.eval

import ammonite.ops._
import mill.util.{TestEvaluator, TestUtil}
import mill.T
import mill.define.Discover
import mill.util.TestEvaluator.implicitDisover
import utest._


object ModuleTests extends TestSuite{
  object ExternalModule extends mill.define.ExternalModule {
    def x = T{13}
    object inner extends mill.Module{
      def y = T{17}
    }
    def millDiscover = Discover[this.type]
  }
  object Build extends TestUtil.BaseModule{
    def z = T{ ExternalModule.x() + ExternalModule.inner.y() }
  }
  val tests = Tests {
    rm(TestEvaluator.externalOutPath)
    'externalModuleTargetsAreNamespacedByModulePackagePath - {
      val check = new TestEvaluator(Build)

      val Right((30, 1)) = check.apply(Build.z)
      assert(
        read(check.evaluator.outPath / 'z / "meta.json").contains("30"),
        read(TestEvaluator.externalOutPath / 'mill / 'eval / 'ModuleTests / 'ExternalModule / 'x / "meta.json").contains("13"),
        read(TestEvaluator.externalOutPath / 'mill / 'eval / 'ModuleTests / 'ExternalModule / 'inner / 'y / "meta.json").contains("17")
      )
    }
    'externalModuleMustBeGlobalStatic - {


      object Build extends mill.define.ExternalModule {

        def z = T{ ExternalModule.x() + ExternalModule.inner.y() }
        def millDiscover = Discover[this.type]
      }

      intercept[java.lang.AssertionError]{ Build }
    }
  }
}
