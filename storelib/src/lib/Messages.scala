package ddbt.lib

/**
 * This object encapsulates all the function that are related to messages:
 * 1. External events: streams events, and collecting result
 * 2. Internal messages exchanged between peers using batching,
 *    acknowledgments and custom serialization.
 *
 * @author TCK, Milos Nikolic
 */
sealed trait Msg
object Messages {
  // --------------- External messages
  type TupleOp = Byte
  final val TupleDelete: TupleOp = -1
  final val TupleInsert: TupleOp = +1

  /** Stream event messages sent by sources to the system. */
  abstract sealed class StreamEvent
  abstract sealed class InputEvent extends StreamEvent 
  case class OrderedInputEvent(ord: Int, event: InputEvent)

  // Single-tuple insertions/deletions and batch updates
  case class TupleEvent(op: TupleOp, stream: String, data: List[Any]) extends InputEvent   // XXX: serialize stream name as Byte/Int(hashCode?)
  case class BatchUpdateEvent(streamData: List[(String, Array[List[Any]])])  extends InputEvent // XXX: serialize stream name as Byte/Int(hashCode?)
  // Command messages
  case class StreamInit(timeout: Long = 0L) extends StreamEvent // timeout in ms
  case class GetSnapshot(view: List[Int]) extends StreamEvent // request a snapshot of some maps
  case class GetStream(view: Int, withTupleOp: Boolean) extends StreamEvent // get stream of update
  /** System state (returned with snapshot) */
  case class StreamStat(ns: Long, count: Long, skip: Long) { 
    override def toString = { 
      val ms = math.round(ns/1000000.0)
      "%d.%03ds (%d/%d)".format(ms/1000, ms%1000, count, skip)
    } 
  }
  case object EndOfStream extends StreamEvent // get snapshot of all query maps and shut the system down
  
  // --------------- Internal cluster messages
  import java.io._
  import java.util.Date

  // Internal types are native integers in the Short range (for serialization)
  type MapRef = Int  // map reference
  type FunRef = Int  // function reference
  type NodeRef = Int // node reference: workers are 0..N-1, master_ref = # workers
  def MapRef(i: Int): MapRef = i
  def FunRef(i: Int, internal: Boolean = false): FunRef = if (internal) java.lang.Short.MIN_VALUE + i else i
  def NodeRef(i: Int): NodeRef = i

  // Note: by using NodeRef instead of ActorRef, we decouple from Akka architecture
  // and possibly allow replication by having virtual nodes spanning over multiple
  // hosts (this can also be achieved using Akka Routing).
  // XXX: find a way to support duplicate responses for AggPart

  /** Data (internal) messages */
  //abstract sealed class Msg // data message (can be batched)
  case class Get[K](map: MapRef, key: K) extends Msg // => Val[K,V]
  case class Val[K, V](map: MapRef, key: K, value: V) extends Msg
  case class Add[K, V](map: MapRef, key: K, value: V) extends Msg
  case class Set[K, V](map: MapRef, key: K, value: V) extends Msg
  case class Clear[P](map: MapRef, part: Int, partKey: P) extends Msg
  case class Foreach(f: FunRef, args: Array[Any]) extends Msg
  case class Agg(id: Int, f: FunRef, args: Array[Any]) extends Msg // => AggPart(id,_)
  case class AggPart[R](id: Int, res: R) extends Msg
  case class Ack(to: Array[NodeRef], num: Array[Int]) extends Msg // workers->master

  /** Management (internal) messages */
  import akka.actor.ActorRef
  case class Members(master: ActorRef, workers: Array[ActorRef]) // master->workers initialization
  case object Shutdown // tears down cluster nodes
  case object Reset // clear all maps and reload tables

}
