package org.ensime.intg

import akka.event.slf4j.SLF4JLogging
import org.ensime.model._
import org.ensime.api._
import org.ensime.util._
import org.scalatest.WordSpec
import org.scalatest.Matchers

import scala.concurrent.duration._
import pimpathon.file._

import org.ensime.fixture._

class BasicWorkflow extends WordSpec with Matchers
    with IsolatedActorSystemFixture
    with IsolatedServerFixture
    with SLF4JLogging {

  val original = EnsimeConfigFixture.SimpleTestProject

  "Server" should {
    "open the test project" in {
      withActorSystem { implicit actorSystem =>
        withServer { (server, asyncHelper) =>
          val project = server.project
          val sourceRoot = scalaMain(server.config)
          val fooFile = sourceRoot / "org/example/Foo.scala"
          val fooFilePath = fooFile.getAbsolutePath

          // trigger typeCheck
          project.typecheckFiles(List(fooFile))

          asyncHelper.expectAsync(30 seconds, ClearAllScalaNotesEvent)
          asyncHelper.expectAsync(30 seconds, FullTypeCheckCompleteEvent)

          //-----------------------------------------------------------------------------------------------
          // semantic highlighting
          val designations = project.symbolDesignations(SourceFileInfo(fooFile), -1, 299, SourceSymbol.allSymbols)
          designations.file shouldBe fooFile
          assert(designations.syms.contains(SymbolDesignation(12, 19, PackageSymbol)))
          // expected Symbols
          // ((package 12 19) (package 8 11) (trait 40 43) (valField 69 70) (class 100 103) (param 125 126) (class 128 131) (param 133 134) (class 136 142) (operator 156 157) (param 154 155) (functionCall 160 166) (param 158 159) (valField 183 186) (class 193 199) (class 201 204) (valField 214 217) (class 224 227) (functionCall 232 239) (operator 250 251) (valField 256 257) (valField 252 255) (functionCall 261 268) (functionCall 273 283) (valField 269 272)))

          //-----------------------------------------------------------------------------------------------
          // symbolAtPoint
          val symbolAtPointOpt: Option[SymbolInfo] = project.symbolAtPoint(fooFile, 128)

          val intTypeId = symbolAtPointOpt match {
            case Some(SymbolInfo("scala.Int", "Int", Some(_), BasicTypeInfo("Int", typeId, DeclaredAs.Class, "scala.Int", List(), List(), _, None), false, Some(ownerTypeId))) =>
              typeId
            case _ =>
              fail("Symbol at point does not match expectations, expected Int symbol got: " + symbolAtPointOpt)
          }

          val fooClassByNameOpt = project.typeByName("org.example.Foo")
          val fooClassId = fooClassByNameOpt match {
            case Some(BasicTypeInfo("Foo", fooTypeIdVal, DeclaredAs.Class, "org.example.Foo", List(), List(), _, None)) =>
              fooTypeIdVal
            case _ =>
              fail("type by name for Foo class does not match expectations, got: " + fooClassByNameOpt)
          }

          val fooObjectByNameOpt = project.typeByName("org.example.Foo$")
          val fooObjectId = fooObjectByNameOpt match {
            case Some(BasicTypeInfo("Foo$", fooObjectIdVal, DeclaredAs.Object, "org.example.Foo$", List(), List(), Some(OffsetSourcePosition(`fooFile`, 28)), None)) =>
              fooObjectIdVal
            case _ =>
              fail("type by name for Foo object does not match expectations, got: " + fooObjectByNameOpt)
          }

          //-----------------------------------------------------------------------------------------------
          // public symbol search - java.io.File

          val javaSearchSymbol = project.publicSymbolSearch(List("java", "io", "File"), 30)
          assert(javaSearchSymbol.syms.exists {
            case TypeSearchResult("java.io.File", "File", DeclaredAs.Class, Some(_)) => true
            case _ => false
          })

          //-----------------------------------------------------------------------------------------------
          // public symbol search - scala.util.Random
          val scalaSearchSymbol = project.publicSymbolSearch(List("scala", "util", "Random"), 2)
          scalaSearchSymbol match {
            case SymbolSearchResults(List(
              TypeSearchResult("scala.util.Random", "Random", DeclaredAs.Class, Some(_)),
              TypeSearchResult("scala.util.Random$", "Random$", DeclaredAs.Class, Some(_)))) =>
            case _ =>
              fail("Public symbol search does not match expectations, got: " + scalaSearchSymbol)
          }

          //-----------------------------------------------------------------------------------------------
          // type by id

          val typeByIdOpt: Option[TypeInfo] = project.typeById(intTypeId)
          val intTypeInspectInfo = typeByIdOpt match {
            case Some(ti @ BasicTypeInfo("Int", `intTypeId`, DeclaredAs.Class, "scala.Int", List(), List(), Some(_), None)) =>
              ti
            case _ =>
              fail("type by id does not match expectations, got " + typeByIdOpt)
          }

          //-----------------------------------------------------------------------------------------------
          // inspect type by id
          val inspectByIdOpt: Option[TypeInspectInfo] = project.inspectTypeById(intTypeId)

          inspectByIdOpt match {
            case Some(TypeInspectInfo(`intTypeInspectInfo`, Some(intCompanionId), supers, _)) =>
            case _ =>
              fail("inspect by id does not match expectations, got: " + inspectByIdOpt)
          }

          //-----------------------------------------------------------------------------------------------
          // uses of symbol at point

          log.info("------------------------------------222-")

          // FIXME: doing a fresh typecheck is needed to pass the next few tests. Why?
          project.typecheckFiles(List(fooFile))
          asyncHelper.expectAsync(30 seconds, ClearAllScalaNotesEvent)
          asyncHelper.expectAsync(30 seconds, FullTypeCheckCompleteEvent)

          val useOfSymbolAtPoint: List[ERangePosition] = project.usesOfSymAtPoint(fooFile, 119) // point on testMethod
          useOfSymbolAtPoint match {
            case List(ERangePosition(`fooFilePath`, 114, 110, 172), ERangePosition(`fooFilePath`, 273, 269, 283)) =>
            case _ =>
              fail("rpcUsesOfSymAtPoint not match expectations, got: " + useOfSymbolAtPoint)
          }

          log.info("------------------------------------222-")

          // note that the line numbers appear to have been stripped from the
          // scala library classfiles, so offset/line comes out as zero unless
          // loaded by the pres compiler
          val testMethodSymbolInfo = project.symbolAtPoint(fooFile, 276)
          testMethodSymbolInfo match {
            case Some(SymbolInfo("testMethod", "testMethod", Some(OffsetSourcePosition(`fooFile`, 114)), ArrowTypeInfo("(i: Int, s: String)Int", 126, BasicTypeInfo("Int", 1, DeclaredAs.Class, "scala.Int", List(), List(), None, None), List(ParamSectionInfo(List((i, BasicTypeInfo("Int", 1, DeclaredAs.Class, "scala.Int", List(), List(), None, None)), (s, BasicTypeInfo("String", 39, DeclaredAs.Class, "java.lang.String", List(), List(), None, None))), false))), true, Some(_))) =>
            case _ =>
              fail("symbol at point (local test method), got: " + testMethodSymbolInfo)
          }

          // M-.  external symbol
          val genericMethodSymbolAtPointRes = project.symbolAtPoint(fooFile, 190)
          genericMethodSymbolAtPointRes match {

            case Some(SymbolInfo("apply", "apply", Some(_),
              ArrowTypeInfo("[A, B](elems: (A, B)*)CC[A,B]", _,
                BasicTypeInfo("CC", _, DeclaredAs.Nil, "scala.collection.generic.CC",
                  List(
                    BasicTypeInfo("A", _, DeclaredAs.Nil, "scala.collection.generic.A", List(), List(), None, None),
                    BasicTypeInfo("B", _, DeclaredAs.Nil, "scala.collection.generic.B", List(), List(), None, None)
                    ), List(), None, None),
                List(ParamSectionInfo(List(
                  ("elems", BasicTypeInfo("<repeated>", _, DeclaredAs.Class, "scala.<repeated>", List(
                    BasicTypeInfo("Tuple2", _, DeclaredAs.Class, "scala.Tuple2", List(
                      BasicTypeInfo("A", _, DeclaredAs.Nil, "scala.collection.generic.A", List(), List(), None, None),
                      BasicTypeInfo("B", _, DeclaredAs.Nil, "scala.collection.generic.B", List(), List(), None, None)
                      ), List(), None, None)), List(), None, None))), false))), true, Some(_))) =>
            case _ =>
              fail("symbol at point (local test method), got: " + genericMethodSymbolAtPointRes)
          }

          // C-c C-v p Inspect source of current package
          val insPacByPathResOpt = project.inspectPackageByPath("org.example")
          insPacByPathResOpt match {
            case Some(PackageInfo("example", "org.example", List(
              BasicTypeInfo("Bloo", _: Int, DeclaredAs.Class, "org.example.Bloo", List(), List(), Some(_), None),
              BasicTypeInfo("Bloo$", _: Int, DeclaredAs.Object, "org.example.Bloo$", List(), List(), Some(_), None),
              BasicTypeInfo("CaseClassWithCamelCaseName", _: Int, DeclaredAs.Class, "org.example.CaseClassWithCamelCaseName", List(), List(), Some(_), None),
              BasicTypeInfo("CaseClassWithCamelCaseName$", _: Int, DeclaredAs.Object, "org.example.CaseClassWithCamelCaseName$", List(), List(), Some(_), None),
              BasicTypeInfo("Foo", _: Int, DeclaredAs.Class, "org.example.Foo", List(), List(), None, None),
              BasicTypeInfo("Foo$", _: Int, DeclaredAs.Object, "org.example.Foo$", List(), List(), Some(_), None),
              BasicTypeInfo("package$", _: Int, DeclaredAs.Object, "org.example.package$", List(), List(), None, None),
              BasicTypeInfo("package$", _: Int, DeclaredAs.Object, "org.example.package$", List(), List(), None, None)))) =>
            case _ =>
              fail("inspect package by path failed, got: " + insPacByPathResOpt)
          }

          // expand selection around 'val foo'
          val expandRange1: FileRange = project.expandSelection(fooFile, 215, 215)
          assert(expandRange1 == FileRange(fooFilePath, 214, 217))

          val expandRange2: FileRange = project.expandSelection(fooFile, 214, 217)
          assert(expandRange2 == FileRange(fooFilePath, 210, 229))

          // TODO get the before content of the file

          // rename var
          val prepareRefactorRes = project.prepareRefactor(1234, RenameRefactorDesc("bar", fooFile, 215, 215))
          log.info("PREPARE REFACTOR = " + prepareRefactorRes)
          prepareRefactorRes match {
            case Right(RefactorEffect(1234, RefactorType.Rename, List(
              TextEdit(`fooFile`, 214, 217, "bar"),
              TextEdit(`fooFile`, 252, 255, "bar"),
              TextEdit(`fooFile`, 269, 272, "bar")), _)) =>
            case _ =>
              fail("Prepare refactor result does not match, got: " + prepareRefactorRes)
          }

          val execRefactorRes = project.execRefactor(1234, RefactorType.Rename)
          execRefactorRes match {
            case Right(RefactorResult(1234, RefactorType.Rename, List(`fooFile`), _)) =>
            case _ =>
              fail("exec refactor does not match expectation: " + execRefactorRes)
          }

          // TODO Check the after refactoring file is different

          val peekUndoRes = project.peekUndo()
          val undoId = peekUndoRes match {
            case Some(Undo(undoIdVal, "Refactoring of type: 'rename", List(TextEdit(`fooFile`, 214, 217, "foo"), TextEdit(`fooFile`, 252, 255, "foo"), TextEdit(`fooFile`, 269, 272, "foo")))) =>
              undoIdVal
            case _ =>
              fail("unexpected peek undo result: " + peekUndoRes)

          }

          val execUndoRes = project.execUndo(undoId)
          execUndoRes match {
            case Right(UndoResult(1, List(`fooFile`))) =>
            case _ =>
              fail("unexpected exec undo result: " + execUndoRes)
          }

          // TODO Check the file has reverted to original

          val packageMemberCompRes = project.packageMemberCompletion("scala.collection.mutable", "Ma")
          packageMemberCompRes match {
            case List(
              CompletionInfo("Map", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("Map$", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("MapBuilder", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("MapBuilder$", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("MapLike", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("MapLike$", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("MapProxy", CompletionSignature(List(), ""), -1, false, 50, None),
              CompletionInfo("MapProxy$", CompletionSignature(List(), ""), -1, false, 50, None)) =>
            case _ =>
              fail("package name completion result: " + packageMemberCompRes)
          }
        }
      }
    }
  }

}
