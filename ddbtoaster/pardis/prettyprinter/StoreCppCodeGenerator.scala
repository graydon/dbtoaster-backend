package ddbt.codegen.prettyprinter

import ch.epfl.data.sc.pardis.ir.CNodes.StrStr
import ch.epfl.data.sc.pardis.ir.CTypes.PointerType
import ch.epfl.data.sc.pardis.ir._
import ch.epfl.data.sc.pardis.prettyprinter.CCodeGenerator
import ch.epfl.data.sc.pardis.types._
import ch.epfl.data.sc.pardis.utils.document._
import ddbt.codegen.Optimizer
import ddbt.lib.store.{IHash, IList}
import ddbt.lib.store.deep.{StoreDSL, StructFieldDecr, StructFieldIncr}
import ddbt.transformer.{Index, IndexesFlag, ScalaConstructsToCTranformer}
import sun.security.x509.CRLDistributionPointsExtension


/**
  * Created by sachin on 28.04.16.
  */
class StoreCppCodeGenerator(override val IR: StoreDSL) extends CCodeGenerator with StoreCodeGenerator {

  import IR._

  val refSymbols = collection.mutable.ArrayBuffer[Sym[_]]()

  override def stmtToDocument(stmt: Statement[_]): Document = stmt match {
    case Statement(sym, StringExtraStringPrintfObject(Constant(size), f, Def(LiftedSeq(args)))) =>
      def ArgToDoc(arg: Rep[_]) = arg.tp match {
        case StringType => doc"$arg.data_"
        case DateType => doc"IntToStrdate($arg)"
        case _ => doc"$arg"
      }
      doc"PString $sym($size);" :\\: doc"snprintf($sym.data_, ${size + 1}, $f, ${args.map(ArgToDoc).mkDocument(", ")});"
    case Statement(sym, StrStr(x, y)) => doc"char* ${sym} = strstr($x.data_, $y);"

    case Statement(sym, EntryIdxGenericCmpObject(_, _)) => Document.empty
    case Statement(sym, EntryIdxGenericOpsObject(_)) => Document.empty
    case Statement(sym, EntryIdxGenericFixedRangeOpsObject(_)) => Document.empty
    case Statement(sym, ab@ArrayApplyObject(_)) if ab.typeT.isInstanceOf[EntryIdxType[_]] => Document.empty

    case Statement(sym, ab@ArrayApplyObject(Def(LiftedSeq(ops)))) => doc"${sym.tp} $sym = { ${ops.mkDocument(",")} };"
    case Statement(sym, ArrayNew(size)) => doc"${sym.tp.asInstanceOf[ArrayType[_]].elementType} $sym[$size];"
    //    case Statement(sym, ArrayUpdate(self, i, r@Constant(rhs: String))) => doc"strcpy($self[$i], $r);"
    case Statement(sym, ArrayUpdate(self, i, x)) => doc"$self[$i] = $x;"

    case Statement(sym, ab@ArrayBufferNew2()) => doc"vector<${ab.typeA}*> $sym;"
    case Statement(sym, ArrayBufferSortWith(self, f)) => doc"sort($self.begin(), $self.end(), $f);"

    case Statement(sym, s@SetApplyObject1(Def(PardisLiftedSeq(es)))) => doc"unordered_set<${s.typeT}> $sym(${es.mkDocument("{", ", ", "}")}); //setApply1"
    case Statement(sym, s@SetApplyObject2()) => doc"unordered_set<${s.typeT}> $sym; //setApply2"
    case Statement(sym, `Set+=`(self, elem)) => doc"$self.insert($elem);"


    case Statement(sym, agg@AggregatorMaxObject(f)) =>
      doc"${agg.typeE}* ${sym}result;" :/:
        doc"MaxAggregator<${agg.typeE}, ${agg.typeR}> $sym($f, &${sym}result);"
    case Statement(sym, agg@AggregatorMinObject(f)) =>
      doc"${agg.typeE}* ${sym}result;" :/:
        doc"MinAggregator<${agg.typeE}, ${agg.typeR}> $sym($f, &${sym}result);"

    case Statement(sym, StoreNew3(_, Def(ArrayApplyObject(Def(LiftedSeq(ops)))))) =>
      val entryTp = sym.tp.asInstanceOf[StoreType[_]].typeE match {
        case PointerType(tp) => tp
        case tp => tp
      }

      val names = ops.collect {
        //TODO: COMMON part. Move.
        case Def(EntryIdxApplyObject(_, _, Constant(name))) => name
        case Def(n: EntryIdxGenericOpsObject) =>
          val cols = n.cols.asInstanceOf[Constant[List[Int]]].underlying.mkString("")
          if (cols.isEmpty)
            s"GenericOps"
          else {
            s"GenericOps_$cols"
          }
        case Def(n: EntryIdxGenericCmpObject[_]) =>
          val ord = Def.unapply(n.f).get.asInstanceOf[PardisLambda[_, _]].o.stmts(0).rhs match {
            case GenericEntryGet(_, Constant(i)) => i
          }
          val cols = n.cols.asInstanceOf[Constant[List[Int]]].underlying.mkString("")
          s"GenericCmp_${cols.mkString("")}_$ord"

        case Def(n: EntryIdxGenericFixedRangeOpsObject) =>
          val cols = n.colsRange.asInstanceOf[Constant[List[(Int, Int, Int)]]].underlying.map(t => s"${t._1}f${t._2}t${t._3}").mkString("_")
          s"GenericFixedRange_$cols"

      }
      val idxes = sym.attributes.get(IndexesFlag).get.indexes
      def idxToDoc(t: (Index, String)) = t._1.tp match {
        case IHash => doc"HashIndex<$entryTp, char, ${t._2}, ${if (t._1.unique) "1" else "0"}>"
        case IList => doc"ListIndex<$entryTp, char, ${t._2},  ${if (t._1.unique) "1" else "0"}>"
      }
      val idxTypeDefs = idxes.zip(names).map(t => doc"typedef ${idxToDoc(t)} ${sym}_Idx_${t._1.idxNum}_Type;").mkDocument("\n")
      idxTypeDefs :/:
        doc"MultiHashMap<$entryTp, char," :: idxes.map(i => doc"${sym}_Idx_${i.idxNum}_Type").mkDocument(", ") :: doc"> $sym;"

    case Statement(sym, StoreIndex(self, idxNum, _, _, _)) => doc"${self}_Idx_${idxNum}_Type& $sym = * (${self}_Idx_${idxNum}_Type *)$self.index[$idxNum];"


    case Statement(sym, IdxSliceRes(idx@Def(StoreIndex(self, idxNum, _, _, _)), key)) if Optimizer.sliceInline =>
      val h = "h" + sym.id
      val IDX_FN = "IDXFN" + sym.id

      doc"//sliceRes " :\\:
        doc"typedef typename ${self}Idx${idxNum}Type::IFN $IDX_FN;" :\\:
        doc"HASH_RES_t $h = $IDX_FN::hash($key);" :\\:
        doc"auto* $sym = &($idx.buckets_[$h % $idx.size_]);" :\\:
        doc"if($sym -> obj)" :\\:
        doc"   do if($h == $sym->hash && !$IDX_FN::cmp($key, *$sym->obj))" :\\:
        doc"     break;" :\\:
        doc"   while(($sym = $sym->nxt));" :\\:
        doc"else " :\\:
        doc"   $sym = nullptr;"

    case Statement(sym, IdxSliceRes(idx, key)) => doc"auto* $sym = $idx.sliceRes($key);"

    case Statement(sym, IdxSliceResMap(idx@Def(StoreIndex(self, idxNum, _, _, _)), key, Def(PardisLambda(_, i, o)), res: Sym[_])) if Optimizer.sliceInline =>
      val h = "h" + res.id
      val IDX_FN = "IDXFN" + res.id

      val pre = doc"//sliceResMap " :\\:
        doc" do if($h== $res->hash && !$IDX_FN::cmp($key, *$res->obj)) {" :\\:
        doc"    auto $i = $res->obj;"
      val post = doc"} while(($res = $res->nxt));"
      pre :/: Document.nest(4, blockToDocument(o)) :/: post

    case Statement(sym, IdxSliceResMap(idx, key, f, res: Sym[_])) => doc"$idx.sliceResMap($key, $f, $res);"

    case Statement(sym, IdxForeachRes(idx@Def(StoreIndex(self, Constant(idxNum), _, _, _)))) if Optimizer.sliceInline => doc"auto* $sym = $idx.dataHead;"

    case Statement(sym, IdxForeachRes(idx)) => doc"auto* $sym = $idx.foreachRes();"

    case Statement(sym, IdxForeachResMap(idx@Def(StoreIndex(self, Constant(idxNum), _, _, _)), Def(PardisLambda(_, i, o)), res: Sym[_])) if Optimizer.sliceInline =>
      val pre =
        doc"//foreach" :\\:
          doc"${i.tp} $i= $res;" :\\:
          doc"while($i) {"
      val post =
        doc"  $i = $i -> nxt;" :\\:
          doc"}"
      pre :/: Document.nest(2, blockToDocument(o)) :/: post

    case Statement(sym, IdxForeachResMap(idx, f, res: Sym[_])) => doc"$idx.foreachResMap($f, $res);"


    case Statement(sym, IdxSlice(idx@Def(StoreIndex(self, idxNum, _, _, _)), key, Def(PardisLambda(_, i, o)))) if Optimizer.sliceInline =>
      val symid = sym.id.toString

      val IDX_FN = "IDXFN" + sym.id
      val pre =
        doc"//slice " :\\:
          doc"typedef typename ${self}Idx${idxNum}Type::IFN $IDX_FN;" :\\:
          doc"HASH_RES_t h$symid = $IDX_FN::hash($key);" :\\:
          doc"auto* n$symid = &($idx.buckets_[h$symid % $idx.size_]);" :\\:
          doc"if(n$symid -> obj)" :\\:
          doc"  do if(h$symid == n$symid->hash && !$IDX_FN::cmp($key, *n$symid->obj)) {" :\\:
          doc"    auto $i = n$symid->obj;"
      val post =
          doc"  } while((n$symid = n$symid->nxt));"
      pre :/: Document.nest(4, blockToDocument(o)) :/: post

    case Statement(sym, IdxForeach(idx@Def(StoreIndex(self, Constant(idxNum), _, _, _)), Def(PardisLambda(_, i, o)))) if Optimizer.sliceInline =>
      val symid = sym.id.toString
      if (idxNum != 0)
        throw new Error("For-each on secondary index !")
      val pre =
        doc"//foreach" :\\:
          doc"${i.tp} $i= $idx.dataHead;" :\\:
          doc"while($i) {"
      val post =
        doc"  $i = $i -> nxt;" :\\:
          doc"}"
      pre :/: Document.nest(2, blockToDocument(o)) :/: post

    case Statement(sym, StructFieldGetter(self: Sym[_], idx)) if sym.tp == StringType && refSymbols.contains(self) =>
      doc"const PString& $sym = $self.$idx;"
    case Statement(sym, StructFieldGetter(self: Sym[_], idx)) if sym.tp == StringType =>
      doc"const PString& $sym = $self->$idx;"

    case _ => super.stmtToDocument(stmt)
  }

  override def symToDocument(sym: ExpressionSymbol[_]): Document = if (sym.tp == UnitType)
    doc"()"
  else {
    if (sym.name != "x" && sym.name != "ite") {
      Document.text(sym.name.stripSuffix("_$"))
    } else {
      super.symToDocument(sym)
    }
  }

  override def expToDocument(exp: Expression[_]): Document = exp match {
    case Constant(null) if exp.tp == DateType => "0"
    case Constant(null) if exp.tp == StringType => "PString()"
    case _ => super.expToDocument(exp)
  }

  override def tpeToDocument[T](tp: TypeRep[T]): Document = tp match {
    case StringType => "PString"
    case DateType => "date"
    case IR.ArrayType(atp) => doc"$atp*"
    case PardisVariableType(vtp) => tpeToDocument(vtp)
    case _ => super.tpeToDocument(tp)
  }

  override def nodeToDocument(node: PardisNode[_]): Document = node match {
    //    case ToString(a) if a.tp == DateType => doc"IntToStrDate($a)"
    case StringSubstring2(self, pos, len) => doc"$self.substr($pos, $len)" //Different from scala substring
    case StringExtraStringCompareObject(str1, str2) => doc"strcmpi($str1.data_, $str2.data_)"

    case MultiResIsEmpty(self: Sym[_]) => doc"$self == nullptr"

    case StoreInsert(self, e) => doc"$self.add($e)"
    case StoreUnsafeInsert(self, e) => doc"$self.insert_nocheck($e)"
    case StoreGet(self, idx, key) => doc"$self.get($key, $idx)"
    case StoreGetCopy(self, idx, key) => doc"$self.getCopy($key, $idx)"
    case StoreGetCopyDependent(self, idx, key) => doc"$self.getCopyDependent($key, $idx)"
    case StoreUpdate(self, key) => doc"$self.update($key)"
    case StoreUpdateCopy(self, key) => doc"$self.updateCopy($key)"
    case StoreUpdateCopyDependent(self, key) => doc"$self.updateCopyDependent($key)"
    case StoreDelete1(self, key) => doc"$self.del($key)"
    case StoreDeleteCopyDependent(self, key) => doc"$self.delCopyDependent($key)"
    case StoreDeleteCopy(self, key) => doc"$self.delCopy($key)"
    case StoreSlice(self, idx, key, f) => doc"$self.slice($idx, $key, $f)"
    case StoreSliceCopy(self, idx, key, f) => doc"$self.sliceCopy($idx, $key, $f)"
    case StoreSliceCopyDependent(self, idx, key, f) => doc"$self.sliceCopyDependent($idx, $key, $f)"
    case StoreForeach(self, f) => doc"$self.foreach($f)"
    case StoreClear(self) => doc"$self.clear()"
    case StoreCopyIntoPool(self, e) => doc"$self.copyIntoPool($e)"

    case IdxGet(self, key) => doc"$self.get($key)"
    case IdxGetCopy(self, key) => doc"$self.getCopy($key)"
    case IdxGetCopyDependent(self, key) => doc"$self.getCopyDependent($key)"
    case IdxUpdate(self, key) => doc"$self.update($key)"
    case IdxUpdateCopy(self, key, primary) => doc"$self.updateCopy($key, &$primary)"
    case IdxUpdateCopyDependent(self, key, ref) => doc"$self.updateCopyDependent($key, $ref)"
    case IdxDelete(self, key) => doc"$self.del($key)"
    case IdxDeleteCopy(self, key, primary) => doc"$self.delCopy($key, &$primary)"
    case IdxDeleteCopyDependent(self, key) => doc"$self.delCopyDependent($key)"
    case IdxSlice(self, key, f) => doc"$self.slice($key, $f)"
    case IdxSliceCopy(self, key, f) => doc"$self.sliceCopy($key, $f)"
    case IdxSliceCopyDependent(self, key, f) => doc"$self.sliceCopyDependent($key, $f)"
    case IdxForeach(self, f) => doc"$self.foreach($f)"
    case IdxClear(self) => doc"$self.clear()"

    case ArrayBufferAppend(self, elem) => doc"$self.push_back($elem)"
    case ArrayBufferApply(Def(ArrayBufferSortWith(self, _)), i) => doc"$self[$i]"
    case ArrayBufferApply(self, i) => doc"$self[$i]"
    case ArrayBufferSize(self) => doc"$self.size()"

    case SetSize(self) => doc"$self.size()"

    case ArrayApply(self, i) => doc"$self[$i]"

    case StructFieldSetter(self: Sym[_], idx, rhs) if refSymbols.contains(self) => doc"$self.$idx = $rhs"
    case StructFieldGetter(self: Sym[_], idx) if refSymbols.contains(self) => doc"$self.$idx"
    case StructFieldIncr(self, idx, rhs) if refSymbols.contains(self) => doc"$self.$idx += $rhs"
    case StructFieldDecr(self, idx, rhs) if refSymbols.contains(self) => doc"$self.$idx -= $rhs"
    case StructFieldIncr(self, idx, rhs) => doc"$self->$idx += $rhs"
    case StructFieldDecr(self, idx, rhs) => doc"$self->$idx -= $rhs"

    case SteNewSEntry(_, args) => doc"new GenericEntry(false_type(), ${args.mkDocument(", ")})"
    case SteSampleSEntry(_, args) =>
      val newargs = args.map(t => List(Constant(t._1), t._2)).flatten
      val argsDoc = newargs.mkDocument(", ")
      doc"new GenericEntry(true_type(), $argsDoc)"
    case GenericEntryApplyObject(Constant("SteSampleSEntry"), Def(LiftedSeq(args))) =>
      val newargs = args.zipWithIndex.collect({
        case (a, i) if i < args.size / 2 => List(a, args(i + args.size / 2))
      }).flatten
      val argsDoc = newargs.mkDocument(", ")
      doc"new GenericEntry(true_type(), $argsDoc)"
    case GenericEntryApplyObject(_, Def(LiftedSeq(args))) => doc"new GenericEntry(false_type(), ${args.mkDocument(", ")})"
    case g@GenericEntryGet(self, i) => doc"$self->get${g.typeE.name}($i)"
    case GenericEntryIncrease(self, i, v) => doc"$self->increase($i, $v)"
    case GenericEntry$plus$eq(self, i, v) => doc"$self->increase($i, $v)"
    case GenericEntryDecrease(self, i, v) => doc"$self->decrease($i, $v)"
    case GenericEntry$minus$eq(self, i, v) => doc"$self->decrease($i, $v)"
    case GenericEntryUpdate(self, i, v) => doc"$self->update($i, $v)"


    case LiftedSeq(ops) if node.tp.isInstanceOf[SeqType[EntryIdx[_]]] => Document.empty
    case PardisLambda(_, i, o) =>
      doc"[&](${i.tp} $i) {" :: Document.nest(NEST_COUNT, blockToDocument(o) :/: getBlockResult(o, true)) :/: "}"

    //    case PardisLambda2(_, i1, i2, o) if refSymbols.contains(i1) =>
    //      val t1 = i1.tp.typeArguments match {
    //        case content :: tail => content
    //        case Nil => i1.tp
    //      }
    //      val t2 = i2.tp.typeArguments match {
    //        case content :: tail => content
    //        case Nil => i2.tp
    //      }
    //      "[&](" :: tpeToDocument(t1) :: " & " :: expToDocument(i1) :: ", " :: tpeToDocument(t2) :: " & " :: expToDocument(i2) :: ") {" :/: Document.nest(NEST_COUNT, blockToDocument(o) :/: getBlockResult(o, true)) :/: "}"
    //
    case PardisLambda2(_, i1, i2, o) =>
      doc"[&](${i1.tp} $i1, ${i2.tp} $i2) {" :/: Document.nest(NEST_COUNT, blockToDocument(o) :/: getBlockResult(o, true)) :/: "}"

    case BooleanExtraConditionalObject(cond, ift, iff) => doc"$cond ? $ift : $iff"

    case `Int>>>1`(self, Constant(v)) if (v < 0) => doc"$self >> ${v & 31}"
    case `Int>>>1`(self, x) => doc"$self >> $x"
    case Equal(a, Constant(null)) if a.tp == StringType => doc"$a.data_ == nullptr"
    //    case Equal(a, b) if a.tp == StringType => //doc"!strcmpi($a, $b)"
    case EntryIdxApplyObject(Def(h: PardisLambda[_, _]), Def(c: PardisLambda2[_, _, _]), Constant(name)) =>
      refSymbols ++= List(h.i, c.i1, c.i2).map(_.asInstanceOf[Sym[_]])
      doc" struct $name {" :/: Document.nest(NEST_COUNT,
        doc"#define int unsigned int" :\\:
          doc"FORCE_INLINE static size_t hash(const ${h.i.tp}& ${h.i})  { " :: Document.nest(NEST_COUNT, blockToDocument(h.o) :/: getBlockResult(h.o, true)) :/: "}" :\\:
          doc"#undef int" :\\:
          doc"FORCE_INLINE static char cmp(const ${c.i1.tp}& ${c.i1}, const ${c.i2.tp}& ${c.i2}) { " :: Document.nest(NEST_COUNT, blockToDocument(c.o) :/: getBlockResult(c.o, true)) :/: "}") :/: "};"

    case EntryIdxGenericOpsObject(Constant(cols)) =>
      if (cols != Nil) {
        val name = "GenericOps_" + cols.mkString("")
        val hash = doc"FORCE_INLINE static size_t hash(const GenericEntry& e) {" :/: Document.nest(2,
          "size_t h = 0;" :/:
            s"for(int c : {${cols.mkString(", ")}})" :/:
            "  h = h ^ (HASH(e.map.at(c)) + 0x9e3779b9 + (h<<6) + (h>>2));" :/:
            "return h;"
        ) :/: "}"
        val cmp = doc"FORCE_INLINE static char cmp(const GenericEntry& e1, const GenericEntry& e2) { " :/: Document.nest(2,
          s"""if (e1.isSampleEntry) {
              |  for (auto it : e1.map) {
              |    if (e2.map.at(it.first) != it.second)
              |        return 1;
              |  }
              |} else if (e2.isSampleEntry) {
              | for (auto it : e2.map) {
              |     if (e1.map.at(it.first) != it.second)
              |         return 1;
              |  }
              |}else {
              |  for(int c : {${cols.mkString(", ")}})
              |    if(e1.map.at(c) != e2.map.at(c))
              |      return 1;
              |}
              |return 0;""".stripMargin
        ) :/: "}"
        doc"struct $name {" :/: Document.nest(2, hash :/: cmp) :/: "};"
      } else Document.empty

    case EntryIdxGenericCmpObject(Constant(cols), Def(PardisLambda(_, _, o))) =>
      val ordCol = o.stmts(0).rhs match {
        case GenericEntryGet(_, Constant(i)) => i
      }
      val name = "GenericCmp_" + cols.mkString("") + "_" + ordCol
      val hash = doc"FORCE_INLINE static size_t hash(const GenericEntry& e) {" :/: Document.nest(2,
        "size_t h = 16;" :/:
          s"for(int c : {${cols.mkString("")}})" :/:
          "  h = h * 41 + HASH(e.map.at(c));" :/:
          "return h;"
      ) :/: "}"
      val cmp = doc"FORCE_INLINE static char cmp(const GenericEntry& e1, const GenericEntry& e2) { " :: Document.nest(2,
        s"""
           |const Any &r1 = e1.map.at($ordCol);
           |const Any &r2 = e2.map.at($ordCol);
           |if (r1 == r2)
           |  return 0;
           |else if( r1 < r2)
           |  return -1;
           |else
           |  return 1;
      """.stripMargin) :/: "}"
      doc"struct $name {" :/: Document.nest(2, hash :/: cmp) :/: "};"

    case EntryIdxGenericFixedRangeOpsObject(Constant(cols)) =>
      val name = "GenericFixedRange_" + cols.map(t => s"${t._1}f${t._2}t${t._3}").mkString("_")
      val hash = doc"FORCE_INLINE static size_t hash(const GenericEntry& e) {" :/: Document.nest(2,
        s"""size_t h = 0;
            |int cols[] = {${cols.map(_._1).mkString(", ")}};
            |int lower[] = {${cols.map(_._2).mkString(", ")}};
            |int upper[] = {${cols.map(_._3).mkString(", ")}};
            |for(int i = 0; i < ${cols.size}; ++i)
            |  h = h * (upper[i] - lower[i]) + e.getInt(cols[i]) - lower[i];  //Defined only for int
            |return h;
        """.stripMargin
      ) :/: "}"
      val cmp = doc"FORCE_INLINE static char cmp(const GenericEntry& e1, const GenericEntry& e2) { " :: Document.nest(2, "return 0;") :: "}"
      doc"struct $name {" :/: Document.nest(2, hash :/: cmp) :/: "};"

    case PardisIfThenElse(cond, thenp, elsep) => //more compact compared to super
      "if(" :: expToDocument(cond) :: ") {" :: Document.nest(NEST_COUNT, blockToDocument(thenp)) :/:
        "}" :: {
        if (elsep.stmts.size != 0) " else {" :: Document.nest(NEST_COUNT, blockToDocument(elsep)) :/: "}"
        else Document.text("")
      }
    case HashCode(a) => doc"HASH($a)"
    case _ => super.nodeToDocument(node)
  }
}
